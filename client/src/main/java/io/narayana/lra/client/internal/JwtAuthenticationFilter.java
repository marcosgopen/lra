/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * JAX-RS Client Request Filter that adds JWT authentication to outbound requests
 * to LRA coordinators when JWT authentication is enabled.
 *
 * <p>
 * This filter supports two modes of operation:
 * <ul>
 * <li>Injected JWT: Uses CDI-injected JsonWebToken when available (e.g., in application context)</li>
 * <li>Configured JWT: Uses a pre-configured JWT token from MicroProfile Config</li>
 * </ul>
 *
 * <p>
 * Configuration properties:
 * <ul>
 * <li>{@code lra.coordinator.jwt.enabled} - Enable/disable JWT authentication (default: false)</li>
 * <li>{@code lra.coordinator.jwt.token} - JWT token value (when not using injection)</li>
 * <li>{@code lra.coordinator.jwt.header} - HTTP header name (default: Authorization)</li>
 * <li>{@code lra.coordinator.jwt.prefix} - Token prefix (default: "Bearer ")</li>
 * </ul>
 */
@Provider
@RequestScoped
public class JwtAuthenticationFilter implements ClientRequestFilter {

    private static final String JWT_ENABLED_PROPERTY = "lra.coordinator.jwt.enabled";
    private static final String JWT_TOKEN_PROPERTY = "lra.coordinator.jwt.token";
    private static final String JWT_HEADER_PROPERTY = "lra.coordinator.jwt.header";
    private static final String JWT_PREFIX_PROPERTY = "lra.coordinator.jwt.prefix";

    private static final String DEFAULT_HEADER = HttpHeaders.AUTHORIZATION;
    private static final String DEFAULT_PREFIX = "Bearer ";

    @Inject
    private Instance<JsonWebToken> jwtInstance;

    private final Config config;
    private final boolean jwtEnabled;
    private final String jwtHeader;
    private final String jwtPrefix;
    private final Optional<String> configuredToken;

    public JwtAuthenticationFilter() {
        this.config = ConfigProvider.getConfig();
        this.jwtEnabled = config.getOptionalValue(JWT_ENABLED_PROPERTY, Boolean.class).orElse(false);
        this.jwtHeader = config.getOptionalValue(JWT_HEADER_PROPERTY, String.class).orElse(DEFAULT_HEADER);
        this.jwtPrefix = config.getOptionalValue(JWT_PREFIX_PROPERTY, String.class).orElse(DEFAULT_PREFIX);
        this.configuredToken = config.getOptionalValue(JWT_TOKEN_PROPERTY, String.class);
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        if (!jwtEnabled) {
            return;
        }

        // Skip if Authorization header is already set
        if (requestContext.getHeaders().containsKey(jwtHeader)) {
            return;
        }

        String token = getJwtToken();
        if (token != null && !token.trim().isEmpty()) {
            String headerValue = jwtPrefix + token;
            requestContext.getHeaders().putSingle(jwtHeader, headerValue);
        }
    }

    /**
     * Gets the JWT token from available sources.
     * Priority: 1) Injected JWT, 2) Configured JWT token
     */
    protected String getJwtToken() {
        // Try injected JWT first (when running in application context)
        if (jwtInstance != null && !jwtInstance.isUnsatisfied()) {
            try {
                JsonWebToken jwt = jwtInstance.get();
                if (jwt != null) {
                    return jwt.getRawToken();
                }
            } catch (Exception e) {
                // JWT injection not available or failed, fall back to configuration
            }
        }

        // Fall back to configured token
        return configuredToken.orElse(null);
    }

    /**
     * Check if JWT authentication is enabled.
     *
     * @return true if JWT authentication is enabled
     */
    public boolean isJwtEnabled() {
        return jwtEnabled;
    }
}
