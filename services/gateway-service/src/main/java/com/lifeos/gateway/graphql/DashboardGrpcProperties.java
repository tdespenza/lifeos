package com.lifeos.gateway.graphql;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned mTLS/workload settings for the optional GraphQL-to-gRPC dashboard fan-out. */
@ConfigurationProperties(prefix = "gateway.dashboard.grpc")
@Validated
public class DashboardGrpcProperties {

    private boolean enabled;

    @NotNull
    private Duration deadline = Duration.ofSeconds(2);

    @Valid
    @NotNull
    private Service task = new Service("localhost", 10_082);

    @Valid
    @NotNull
    private Service calendar = new Service("localhost", 10_085);

    @Valid
    @NotNull
    private Service finance = new Service("localhost", 10_086);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getDeadline() {
        return deadline;
    }

    public void setDeadline(Duration deadline) {
        this.deadline = deadline;
    }

    public Service getTask() {
        return task;
    }

    public void setTask(Service task) {
        this.task = task;
    }

    public Service getCalendar() {
        return calendar;
    }

    public void setCalendar(Service calendar) {
        this.calendar = calendar;
    }

    public Service getFinance() {
        return finance;
    }

    public void setFinance(Service finance) {
        this.finance = finance;
    }

    @Validated
    public static class Service {

        private String host;

        @Min(1_024)
        @Max(65_535)
        private int port;

        private boolean tlsEnabled;
        private String certificateChain = "";
        private String privateKey = "";
        private String trustCertificateCollection = "";
        private String workloadToken = "";

        public Service() {
            // required for property binding
        }

        Service(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isTlsEnabled() {
            return tlsEnabled;
        }

        public void setTlsEnabled(boolean tlsEnabled) {
            this.tlsEnabled = tlsEnabled;
        }

        public String getCertificateChain() {
            return certificateChain;
        }

        public void setCertificateChain(String certificateChain) {
            this.certificateChain = certificateChain;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getTrustCertificateCollection() {
            return trustCertificateCollection;
        }

        public void setTrustCertificateCollection(String trustCertificateCollection) {
            this.trustCertificateCollection = trustCertificateCollection;
        }

        public String getWorkloadToken() {
            return workloadToken;
        }

        public void setWorkloadToken(String workloadToken) {
            this.workloadToken = workloadToken;
        }
    }
}
