/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.config;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * JWT authentication configuration for LRA coordinator communication.
 */
@ConfigMapping(prefix = "lra.coordinator.jwt")
public interface JwtConfiguration {

    /**
     * Whether JWT authentication is enabled.
     * Defaults to false.
     */
    Optional<Boolean> enabled();

    /**
     * JWT token to use for authentication.
     */
    Optional<String> token();

    /**
     * HTTP header name to use for JWT authentication.
     * Defaults to "Authorization".
     */
    Optional<String> header();

    /**
     * Prefix to add before the JWT token.
     * Defaults to "Bearer ".
     */
    Optional<String> prefix();

    /**
     * Check if JWT authentication is enabled.
     */
    default boolean isEnabled() {
        return enabled().orElse(false);
    }

    /**
     * Get the header name for JWT authentication.
     */
    default String getHeader() {
        return header().orElse("Authorization");
    }

    /**
     * Get the prefix for JWT tokens.
     */
    default String getPrefix() {
        return prefix().orElse("Bearer ");
    }
}