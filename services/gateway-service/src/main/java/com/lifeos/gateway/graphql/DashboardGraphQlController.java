package com.lifeos.gateway.graphql;

import graphql.schema.DataFetchingEnvironment;
import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** GraphQL entry point for the bounded dashboard aggregate. */
@Controller
public class DashboardGraphQlController {

    static final String AUTHORIZATION_ATTRIBUTE = DashboardGraphQlSecurityFilter.AUTHORIZATION_ATTRIBUTE;

    private final DashboardAggregationService aggregationService;

    public DashboardGraphQlController(DashboardAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @QueryMapping
    public DashboardSnapshot dashboard(@Argument Integer periodDays, DataFetchingEnvironment environment) {
        String authorization = environment.getGraphQlContext().get(AUTHORIZATION_ATTRIBUTE);
        GatewayAuthenticatedSubject subject = environment.getGraphQlContext()
                .get(DashboardGraphQlSecurityFilter.SUBJECT_ATTRIBUTE);
        return aggregationService.aggregate(periodDays == null ? 30 : periodDays, authorization, subject);
    }
}
