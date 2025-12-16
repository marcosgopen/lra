/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import io.narayana.lra.client.api.LRACoordinatorService;
import io.narayana.lra.client.config.SslConfiguration;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.net.URI;
import javax.net.ssl.SSLContext;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

/**
 * Factory for creating and configuring REST clients for LRA coordinator communication.
 * Centralizes the logic for REST client configuration including authentication, SSL, and other settings.
 */
public class RestClientFactory {

    /**
     * Configuration for REST client creation.
     */
    public static class ClientConfiguration {
        private ClientRequestFilter authenticationFilter;
        private SSLContext sslContext;
        private boolean hostnameVerificationEnabled = true;
        private SslConfiguration sslConfig;

        public ClientRequestFilter getAuthenticationFilter() {
            return authenticationFilter;
        }

        public ClientConfiguration setAuthenticationFilter(ClientRequestFilter authenticationFilter) {
            this.authenticationFilter = authenticationFilter;
            return this;
        }

        public SSLContext getSslContext() {
            return sslContext;
        }

        public ClientConfiguration setSslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public boolean isHostnameVerificationEnabled() {
            return hostnameVerificationEnabled;
        }

        public ClientConfiguration setHostnameVerificationEnabled(boolean hostnameVerificationEnabled) {
            this.hostnameVerificationEnabled = hostnameVerificationEnabled;
            return this;
        }

        public SslConfiguration getSslConfig() {
            return sslConfig;
        }

        public ClientConfiguration setSslConfig(SslConfiguration sslConfig) {
            this.sslConfig = sslConfig;
            return this;
        }
    }

    /**
     * Creates a configured LRA coordinator REST client.
     *
     * @param coordinatorUri the URI of the coordinator
     * @param configuration the client configuration
     * @return configured LRACoordinatorService client
     */
    public static LRACoordinatorService createClient(URI coordinatorUri, ClientConfiguration configuration) {
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(coordinatorUri);

        configureAuthentication(builder, configuration);
        configureSsl(builder, configuration);
        configureHostnameVerification(builder, configuration);

        return builder.build(LRACoordinatorService.class);
    }

    /**
     * Creates a REST client with minimal configuration.
     *
     * @param coordinatorUri the URI of the coordinator
     * @return basic LRACoordinatorService client
     */
    public static LRACoordinatorService createClient(URI coordinatorUri) {
        return createClient(coordinatorUri, new ClientConfiguration());
    }

    /**
     * Configures authentication for the REST client.
     */
    private static void configureAuthentication(RestClientBuilder builder, ClientConfiguration config) {
        if (config.getAuthenticationFilter() != null) {
            builder.register(config.getAuthenticationFilter());
        }
    }

    /**
     * Configures SSL for the REST client.
     */
    private static void configureSsl(RestClientBuilder builder, ClientConfiguration config) {
        SSLContext sslContext = config.getSslContext();

        if (sslContext != null) {
            builder.sslContext(sslContext);
        } else {
            // Try to create SSL context from configuration
            SSLContext configuredSslContext = createSslContextFromConfig(config.getSslConfig());
            if (configuredSslContext != null) {
                builder.sslContext(configuredSslContext);
            }
        }
    }

    /**
     * Configures hostname verification for the REST client.
     */
    private static void configureHostnameVerification(RestClientBuilder builder, ClientConfiguration config) {
        if (!config.isHostnameVerificationEnabled()) {
            builder.hostnameVerifier((hostname, session) -> true);
        } else {
            // Check configuration-based hostname verification
            SslConfiguration sslConfig = config.getSslConfig();
            if (sslConfig != null && !sslConfig.verifyHostname().orElse(true)) {
                builder.hostnameVerifier((hostname, session) -> true);
            }
        }
    }

    /**
     * Creates an SSL context from SSL configuration.
     *
     * @param sslConfig the SSL configuration (may be null)
     * @return configured SSLContext or null if no valid configuration
     */
    private static SSLContext createSslContextFromConfig(SslConfiguration sslConfig) {
        if (sslConfig == null || !sslConfig.isConfigured()) {
            return null;
        }

        try {
            if (sslConfig.isMutualTlsConfigured()) {
                // Create mutual TLS context
                return SslUtils.createMutualTlsSslContext(
                        sslConfig.truststorePath().orElse(null),
                        sslConfig.truststorePassword().orElse(null),
                        sslConfig.truststoreType().orElse("JKS"),
                        sslConfig.keystorePath().orElse(null),
                        sslConfig.keystorePassword().orElse(null),
                        sslConfig.keystoreType().orElse("JKS"));
            } else if (sslConfig.truststorePath().isPresent()) {
                // Create truststore-only context
                return SslUtils.createSslContextWithTruststore(
                        sslConfig.truststorePath().get(),
                        sslConfig.truststorePassword().orElse(null),
                        sslConfig.truststoreType().orElse("JKS"));
            }
        } catch (Exception e) {
            LRALogger.logger.warn("Failed to create SSL context from configuration", e);
        }

        return null;
    }

    /**
     * Builder for ClientConfiguration.
     */
    public static class ConfigurationBuilder {
        private final ClientConfiguration config = new ClientConfiguration();

        public static ConfigurationBuilder newBuilder() {
            return new ConfigurationBuilder();
        }

        public ConfigurationBuilder withAuthentication(ClientRequestFilter filter) {
            config.setAuthenticationFilter(filter);
            return this;
        }

        public ConfigurationBuilder withSsl(SSLContext sslContext) {
            config.setSslContext(sslContext);
            return this;
        }

        public ConfigurationBuilder withSslConfig(SslConfiguration sslConfig) {
            config.setSslConfig(sslConfig);
            return this;
        }

        public ConfigurationBuilder withHostnameVerification(boolean enabled) {
            config.setHostnameVerificationEnabled(enabled);
            return this;
        }

        public ClientConfiguration build() {
            return config;
        }
    }
}
