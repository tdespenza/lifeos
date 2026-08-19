package com.lifeos.taskgoal.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned settings for the optional internal mTLS gRPC metrics host. */
@ConfigurationProperties(prefix = "grpc.server")
public class GrpcServerProperties {

    private boolean enabled;
    private int port = 10_082;
    private boolean tlsEnabled;
    private String certificateChain = "";
    private String privateKey = "";
    private String trustCertificateCollection = "";
    private String workloadToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
