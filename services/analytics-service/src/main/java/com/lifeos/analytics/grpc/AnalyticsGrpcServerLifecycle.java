package com.lifeos.analytics.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.File;
import java.io.IOException;
import org.springframework.context.SmartLifecycle;

/** Starts the optional internal Analytics gRPC host only with mTLS and workload credentials. */
public final class AnalyticsGrpcServerLifecycle implements SmartLifecycle {

    private final AnalyticsDashboardGrpcService dashboardService;
    private final AnalyticsGrpcServerProperties properties;
    private Server server;

    public AnalyticsGrpcServerLifecycle(
            AnalyticsDashboardGrpcService dashboardService, AnalyticsGrpcServerProperties properties) {
        this.dashboardService = dashboardService;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        validateProperties();
        try {
            SslContext sslContext = GrpcSslContexts.forServer(
                            new File(properties.getCertificateChain()), new File(properties.getPrivateKey()))
                    .trustManager(new File(properties.getTrustCertificateCollection()))
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
            server = NettyServerBuilder.forPort(properties.getPort())
                    .addService(dashboardService)
                    .intercept(new AnalyticsGrpcWorkloadAuthInterceptor(properties.getWorkloadToken()))
                    .sslContext(sslContext)
                    .build()
                    .start();
        } catch (IOException exception) {
            throw new IllegalStateException("analytics gRPC mTLS server failed to start", exception);
        }
    }

    @Override public void stop() { if (server != null) server.shutdown(); }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    private void validateProperties() {
        if (properties.getPort() < 1_024 || properties.getPort() > 65_535 || !properties.isTlsEnabled()
                || properties.getCertificateChain().isBlank() || properties.getPrivateKey().isBlank()
                || properties.getTrustCertificateCollection().isBlank() || properties.getWorkloadToken().isBlank()) {
            throw new IllegalStateException(
                    "enabled analytics gRPC requires a bounded port, mTLS certificate/key/trust files, and workload token");
        }
    }
}
