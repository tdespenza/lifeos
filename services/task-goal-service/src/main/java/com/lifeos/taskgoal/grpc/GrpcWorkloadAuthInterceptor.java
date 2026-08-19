package com.lifeos.taskgoal.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Requires a deployment-owned workload token in addition to the mTLS channel. */
public final class GrpcWorkloadAuthInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> WORKLOAD_TOKEN = Metadata.Key.of(
            "x-lifeos-workload-token", Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expectedToken;

    public GrpcWorkloadAuthInterceptor(String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalArgumentException("gRPC workload token must be configured");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String presented = headers.get(WORKLOAD_TOKEN);
        if (presented == null
                || !MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("workload authentication failed"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        Context context = Context.current().withValue(GrpcServerContext.WORKLOAD_AUTHENTICATED, Boolean.TRUE);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
