/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Utility class for configuring JWT authentication in the LRA client.
 */
public class JwtAuthenticationUtils {

    private static final String JWT_ENABLED_PROPERTY = "lra.coordinator.jwt.enabled";

    /**
     * Creates a JWT authentication filter if JWT authentication is enabled in configuration.
     *
     * @return a JwtAuthenticationFilter instance if JWT is enabled, null otherwise
     */
    public static ClientRequestFilter createJwtFilterIfEnabled() {
        Config config = ConfigProvider.getConfig();
        boolean jwtEnabled = config.getOptionalValue(JWT_ENABLED_PROPERTY, Boolean.class).orElse(false);

        if (jwtEnabled) {
            return new JwtAuthenticationFilter();
        }

        return null;
    }

    /**
     * Creates a JWT authentication filter with explicit configuration.
     *
     * @param jwtToken the JWT token to use for authentication
     * @return a JwtAuthenticationFilter configured with the provided token
     */
    public static ClientRequestFilter createJwtFilter(String jwtToken) {
        return new ConfiguredJwtFilter(jwtToken);
    }

    /**
     * Simple JWT filter implementation that uses a pre-configured token.
     */
    private static class ConfiguredJwtFilter extends JwtAuthenticationFilter {
        private final String token;

        public ConfiguredJwtFilter(String token) {
            this.token = token;
        }

        @Override
        protected String getJwtToken() {
            return token;
        }
    }
}