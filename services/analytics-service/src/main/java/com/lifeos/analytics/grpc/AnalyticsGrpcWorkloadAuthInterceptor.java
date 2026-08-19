package com.lifeos.analytics.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Requires a deployment-provided workload token after the mTLS peer is authenticated. */
public final class AnalyticsGrpcWorkloadAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TOKEN =
            Metadata.Key.of("x-lifeos-workload-token", Metadata.ASCII_STRING_MARSHALLER);
    private final byte[] expectedToken;

    public AnalyticsGrpcWorkloadAuthInterceptor(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String supplied = headers.get(TOKEN);
        if (supplied == null || !MessageDigest.isEqual(expectedToken, supplied.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("workload authentication failed"), new Metadata());
            return new Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
