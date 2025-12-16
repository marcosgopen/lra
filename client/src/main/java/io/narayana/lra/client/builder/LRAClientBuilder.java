/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.builder;

import io.narayana.lra.client.config.JwtConfiguration;
import io.narayana.lra.client.config.LRAClientConfig;
import io.narayana.lra.client.config.LoadBalancerConfiguration;
import io.narayana.lra.client.config.SslConfiguration;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * Builder for creating and configuring NarayanaLRAClient instances.
 * Provides a fluent API for setting up authentication, SSL, and load balancing.
 */
public class LRAClientBuilder {

    private String coordinatorUrl;
    private ClientRequestFilter authenticationFilter;
    private SSLContext sslContext;
    private boolean hostnameVerificationEnabled = true;
    private JwtConfiguration jwtConfig;
    private SslConfiguration sslConfig;
    private LoadBalancerConfiguration lbConfig;
    private LRAClientConfig clientConfig;

    /**
     * Creates a new builder instance.
     */
    public static LRAClientBuilder newBuilder() {
        return new LRAClientBuilder();
    }

    /**
     * Creates a new builder instance with configuration.
     */
    public static LRAClientBuilder newBuilder(LRAClientConfig config) {
        return new LRAClientBuilder().withConfig(config);
    }

    private LRAClientBuilder() {
    }

    /**
     * Set the coordinator URL.
     */
    public LRAClientBuilder coordinatorUrl(String url) {
        this.coordinatorUrl = Objects.requireNonNull(url, "Coordinator URL cannot be null");
        return this;
    }

    /**
     * Set the coordinator URI.
     */
    public LRAClientBuilder coordinatorUrl(URI uri) {
        return coordinatorUrl(Objects.requireNonNull(uri, "Coordinator URI cannot be null").toString());
    }

    /**
     * Set the authentication filter.
     */
    public LRAClientBuilder withAuthentication(ClientRequestFilter filter) {
        this.authenticationFilter = filter;
        return this;
    }

    /**
     * Set the SSL context.
     */
    public LRAClientBuilder withSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Configure hostname verification.
     */
    public LRAClientBuilder hostnameVerification(boolean enabled) {
        this.hostnameVerificationEnabled = enabled;
        return this;
    }

    /**
     * Set JWT configuration.
     */
    public LRAClientBuilder withJwt(JwtConfiguration jwtConfig) {
        this.jwtConfig = jwtConfig;
        return this;
    }

    /**
     * Set SSL configuration.
     */
    public LRAClientBuilder withSsl(SslConfiguration sslConfig) {
        this.sslConfig = sslConfig;
        return this;
    }

    /**
     * Set load balancer configuration.
     */
    public LRAClientBuilder withLoadBalancer(LoadBalancerConfiguration lbConfig) {
        this.lbConfig = lbConfig;
        return this;
    }

    /**
     * Set the complete client configuration.
     */
    public LRAClientBuilder withConfig(LRAClientConfig config) {
        this.clientConfig = config;
        if (config != null) {
            this.coordinatorUrl = config.getCoordinatorUrl();
            this.jwtConfig = config.jwt();
            this.sslConfig = config.ssl();
            this.lbConfig = config.loadBalancer();
            this.hostnameVerificationEnabled = config.ssl().verifyHostname().orElse(true);
        }
        return this;
    }

    /**
     * Build and configure the NarayanaLRAClient instance.
     */
    public NarayanaLRAClient build() {
        // Determine coordinator URL
        String url = Optional.ofNullable(coordinatorUrl)
                .or(() -> Optional.ofNullable(clientConfig).map(LRAClientConfig::getCoordinatorUrl))
                .orElse("http://localhost:8080/lra-coordinator");

        // Create the client
        NarayanaLRAClient client = new NarayanaLRAClient(url);

        // Configure authentication
        if (authenticationFilter != null) {
            client.setAuthenticationFilter(authenticationFilter);
        }

        // Configure SSL
        if (sslContext != null) {
            client.setSslContext(sslContext);
        }

        // Configure hostname verification
        client.setHostnameVerificationEnabled(hostnameVerificationEnabled);

        return client;
    }

    /**
     * Fluent builder for authentication configuration.
     */
    public static class AuthenticationBuilder {
        private final LRAClientBuilder parent;

        AuthenticationBuilder(LRAClientBuilder parent) {
            this.parent = parent;
        }

        /**
         * Set JWT token for authentication.
         */
        public LRAClientBuilder jwt(String token) {
            // Create JWT filter with token
            // This would need to be implemented based on your JWT filter
            return parent;
        }

        /**
         * Set custom authentication filter.
         */
        public LRAClientBuilder custom(ClientRequestFilter filter) {
            return parent.withAuthentication(filter);
        }
    }

    /**
     * Fluent builder for SSL configuration.
     */
    public static class SslBuilder {
        private final LRAClientBuilder parent;

        SslBuilder(LRAClientBuilder parent) {
            this.parent = parent;
        }

        /**
         * Set custom SSL context.
         */
        public LRAClientBuilder context(SSLContext context) {
            return parent.withSslContext(context);
        }

        /**
         * Configure hostname verification.
         */
        public LRAClientBuilder hostnameVerification(boolean enabled) {
            return parent.hostnameVerification(enabled);
        }

        /**
         * Trust all certificates (development only).
         */
        public LRAClientBuilder trustAll() {
            // Would create trust-all SSL context
            return parent.hostnameVerification(false);
        }
    }

    /**
     * Start authentication configuration.
     */
    public AuthenticationBuilder authentication() {
        return new AuthenticationBuilder(this);
    }

    /**
     * Start SSL configuration.
     */
    public SslBuilder ssl() {
        return new SslBuilder(this);
    }
}
