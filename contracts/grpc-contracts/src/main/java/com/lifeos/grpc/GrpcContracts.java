package com.lifeos.grpc;

/**
 * Namespace marker for generated versioned protobuf and gRPC Java APIs.
 *
 * <p>The architecture verifier discovers contract namespaces from hand-maintained source roots,
 * while protobuf code is generated under {@code build/}. Keeping this marker makes
 * {@code com.lifeos.grpc.*} an explicit allowed shared-contract import for future service stubs
 * without allowing direct service-to-service implementation imports.
 */
public final class GrpcContracts {

    private GrpcContracts() {
    }
}
