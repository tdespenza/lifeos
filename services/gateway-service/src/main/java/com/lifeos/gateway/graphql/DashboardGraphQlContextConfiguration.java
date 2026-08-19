package com.lifeos.gateway.graphql;

import org.springframework.http.HttpHeaders;
import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Copies the authenticated request token into the per-request GraphQL context only. */
@Component
public class DashboardGraphQlContextConfiguration implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        GatewayAuthenticatedSubject subject = (GatewayAuthenticatedSubject)
                request.getAttributes().get(DashboardGraphQlSecurityFilter.SUBJECT_ATTRIBUTE);
        request.configureExecutionInput((executionInput, builder) -> builder.graphQLContext(context ->
                        context.put(DashboardGraphQlSecurityFilter.AUTHORIZATION_ATTRIBUTE, authorization)
                                .put(DashboardGraphQlSecurityFilter.SUBJECT_ATTRIBUTE, subject))
                .build());
        return chain.next(request);
    }
}
