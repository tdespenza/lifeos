package com.lifeos.notification.config;

import java.net.InetAddress;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional HTTP provider endpoints; production secrets remain environment/secret-manager inputs. */
@ConfigurationProperties("notification.providers")
public class NotificationProviderProperties {

    private HttpProvider email = new HttpProvider();
    private HttpProvider push = new HttpProvider();
    private boolean localDevelopmentEnabled;

    public HttpProvider getEmail() {
        return email;
    }

    public void setEmail(HttpProvider email) {
        this.email = email;
    }

    public HttpProvider getPush() {
        return push;
    }

    public void setPush(HttpProvider push) {
        this.push = push;
    }

    public boolean isLocalDevelopmentEnabled() {
        return localDevelopmentEnabled;
    }

    public void setLocalDevelopmentEnabled(boolean localDevelopmentEnabled) {
        this.localDevelopmentEnabled = localDevelopmentEnabled;
    }

    public static class HttpProvider {

        private boolean enabled;
        private URI baseUrl;
        private String authorizationToken;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAuthorizationToken() {
            return authorizationToken;
        }

        public void setAuthorizationToken(String authorizationToken) {
            this.authorizationToken = authorizationToken;
        }

        /** Validates only when an adapter is enabled, allowing local realtime-only development. */
        public void validateEnabled() {
            if (!enabled) {
                return;
            }
            if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getRawUserInfo() != null
                    || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                    || !baseUrl.getRawPath().isEmpty()) {
                throw new IllegalStateException("enabled notification provider baseUrl must be an origin");
            }
            if (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !isLoopback(baseUrl.getHost())) {
                throw new IllegalStateException("enabled notification provider must use HTTPS unless loopback");
            }
            if (authorizationToken == null || authorizationToken.isBlank()) {
                throw new IllegalStateException("enabled notification provider authorization token is required");
            }
        }

        private static boolean isLoopback(String host) {
            if (host == null || host.isBlank()) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host)) {
                return true;
            }
            try {
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
