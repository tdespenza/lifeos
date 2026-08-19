package com.lifeos.taskgoal.grpc;

import io.grpc.Context;

/** Internal context marker populated only after workload-token validation. */
final class GrpcServerContext {

    static final Context.Key<Boolean> WORKLOAD_AUTHENTICATED = Context.key("lifeos-workload-authenticated");

    private GrpcServerContext() {}
}
