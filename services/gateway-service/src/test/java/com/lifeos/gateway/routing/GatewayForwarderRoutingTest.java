package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

class GatewayForwarderRoutingTest {

    private static final String ASSET = "/api/v1/media/assets/123e4567-e89b-12d3-a456-426614174000";

    @Test
    void selectsEachPurposeSpecificClientOnlyForItsExactRoutePredicate() {
        GatewayProperties properties = properties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RestClient buffered = mock(RestClient.class);
        RestClient streaming = mock(RestClient.class);
        RestClient documentUpload = mock(RestClient.class);
        RestClient mediaUpload = mock(RestClient.class);
        RestClient mediaHls = mock(RestClient.class);
        RestClient assistant = mock(RestClient.class);
        GatewayForwarder forwarder = new GatewayForwarder(
                buffered,
                streaming,
                documentUpload,
                mediaUpload,
                mediaHls,
                assistant,
                properties,
                registry,
                new GatewayUpstreamResilience(properties, registry),
                new GatewayRetryPolicy(properties));

        assertThat(forwarder.selectClient(notificationRoute(), request(GatewayRoute.NOTIFICATION_STREAM_PATH), HttpMethod.GET))
                .isSameAs(streaming);
        assertThat(forwarder.selectClient(documentRoute(), request(GatewayRoute.DOCUMENT_UPLOAD_PATH), HttpMethod.POST))
                .isSameAs(documentUpload);
        assertThat(forwarder.selectClient(mediaRoute(), request(ASSET + "/source"), HttpMethod.PUT))
                .isSameAs(mediaUpload);
        assertThat(forwarder.selectClient(mediaRoute(), request(ASSET + "/hls/master.m3u8"), HttpMethod.GET))
                .isSameAs(mediaHls);
        assertThat(forwarder.selectClient(assistantRoute(), request("/api/v1/assistant/chat"), HttpMethod.POST))
                .isSameAs(assistant);
        assertThat(forwarder.selectClient(mediaRoute(), request(ASSET + "/metadata"), HttpMethod.GET))
                .isSameAs(buffered);
    }

    @Test
    void admitsMediaUploadAndHlsThroughDedicatedVirtualRouteStates() {
        GatewayProperties properties = properties();
        properties.getUpstream().getBulkhead().setMaxConcurrentRequests(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RestClient client = mock(RestClient.class);
        GatewayForwarder forwarder = new GatewayForwarder(
                client, client, client, client, client, client, properties, registry,
                new GatewayUpstreamResilience(properties, registry), new GatewayRetryPolicy(properties));
        GatewayRoute route = mediaRoute();

        try (GatewayUpstreamResilience.Permit upload = forwarder.acquirePermit(
                        route, request(ASSET + "/source"), HttpMethod.PUT);
                GatewayUpstreamResilience.Permit hls = forwarder.acquirePermit(
                        route, request(ASSET + "/hls/master.m3u8"), HttpMethod.GET);
                GatewayUpstreamResilience.Permit generic = forwarder.acquirePermit(
                        route, request(ASSET + "/metadata"), HttpMethod.GET)) {
            assertThat(generic).isNotNull();
        }
    }

    @Test
    void rejectsOversizedAndFramedRelayRequestsWithoutRetainingTheirBodies() throws Exception {
        HttpServletRequest uploadRequest = request(GatewayRoute.DOCUMENT_UPLOAD_PATH);
        when(uploadRequest.getInputStream()).thenReturn(inputStream("12345"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> GatewayForwarder.copyBounded(uploadRequest, output, 4))
                .isInstanceOf(GatewayPayloadTooLargeException.class);
        assertThat(output.size()).isZero();

        HttpServletRequest hlsRequest = request(ASSET + "/hls/master.m3u8");
        when(hlsRequest.getContentLengthLong()).thenReturn(1L);
        assertThatThrownBy(() -> GatewayForwarder.rejectMediaHlsRequestBodyFraming(hlsRequest))
                .isInstanceOf(GatewayBadRequestException.class);
    }

    @Test
    void rejectsDeclaredOversizedHlsResponseBeforeCommittingIt() throws Exception {
        ClientHttpResponse upstream = mock(ClientHttpResponse.class);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentLength(9);
        when(upstream.getHeaders()).thenReturn(headers);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> GatewayForwarder.writeMediaHlsResponse(response, upstream, mediaRoute(), 8))
                .isInstanceOf(GatewayResponseTooLargeException.class);
        assertThat(response.isCommitted()).isFalse();
    }

    @Test
    void endsHlsRelayNormallyWhenTheDownstreamDisconnectsAfterHeaders() throws Exception {
        ClientHttpResponse upstream = mock(ClientHttpResponse.class);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentLength(1);
        when(upstream.getHeaders()).thenReturn(headers);
        when(upstream.getStatusCode()).thenReturn(org.springframework.http.HttpStatus.OK);
        when(upstream.getBody()).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(disconnectedOutputStream());

        assertThat(GatewayForwarder.writeMediaHlsResponse(response, upstream, mediaRoute(), 8))
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void acceptsBoundedUploadBodiesAndPreservesWrappedOverflowDiagnostics() throws Exception {
        HttpServletRequest uploadRequest = request(GatewayRoute.DOCUMENT_UPLOAD_PATH);
        when(uploadRequest.getInputStream()).thenReturn(inputStream("1234"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        GatewayForwarder.copyBounded(uploadRequest, output, 4);

        IOException cause = new IOException("bounded test overflow");
        GatewayPayloadTooLargeException exception = new GatewayPayloadTooLargeException(cause);
        assertThat(output.toByteArray()).isEqualTo("1234".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getStackTrace()).isEmpty();
    }

    private static HttpServletRequest request(String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        return request;
    }

    private static ServletInputStream inputStream(String body) {
        ByteArrayInputStream input = new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // The relay unit tests use only synchronous servlet reads.
            }
        };
    }

    private static ServletOutputStream disconnectedOutputStream() {
        return new ServletOutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("test downstream disconnect");
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // The relay unit tests use only synchronous servlet writes.
            }
        };
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.Route media = new GatewayProperties.Route(
                "media", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX, "https://media.test");
        media.setMediaUploadStreaming(true);
        media.setMediaHlsStreaming(true);
        properties.setRoutes(List.of(media));
        return properties;
    }

    private static GatewayRoute notificationRoute() {
        return new GatewayRoute("notifications", GatewayRoute.NOTIFICATION_STREAM_PATH,
                URI.create("https://notifications.test"), true, Set.of(), Set.of(), Set.of(), true, false, false, false);
    }

    private static GatewayRoute documentRoute() {
        return new GatewayRoute("documents", GatewayRoute.DOCUMENT_UPLOAD_PATH,
                URI.create("https://documents.test"), true, Set.of(), Set.of(), Set.of(), false, true, false, false);
    }

    private static GatewayRoute mediaRoute() {
        return new GatewayRoute("media", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX,
                URI.create("https://media.test"), true, Set.of(), Set.of(), Set.of(), false, false, true, true);
    }

    private static GatewayRoute assistantRoute() {
        return new GatewayRoute("assistant", GatewayRoute.AI_ASSISTANT_PATH_PREFIX,
                URI.create("https://assistant.test"), true, Set.of(), Set.of(), Set.of(), false, false, false, false);
    }
}
