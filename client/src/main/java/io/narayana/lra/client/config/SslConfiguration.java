/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.config;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * SSL configuration for LRA coordinator communication.
 */
@ConfigMapping(prefix = "lra.coordinator.ssl")
public interface SslConfiguration {

    /**
     * Path to the truststore file.
     */
    Optional<String> truststorePath();

    /**
     * Password for the truststore.
     */
    Optional<String> truststorePassword();

    /**
     * Type of the truststore (JKS, PKCS12, etc.).
     * Defaults to JKS.
     */
    Optional<String> truststoreType();

    /**
     * Path to the keystore file for client certificate authentication.
     */
    Optional<String> keystorePath();

    /**
     * Password for the keystore.
     */
    Optional<String> keystorePassword();

    /**
     * Type of the keystore (JKS, PKCS12, etc.).
     * Defaults to JKS.
     */
    Optional<String> keystoreType();

    /**
     * Whether to verify hostname in SSL certificates.
     * Defaults to true.
     */
    Optional<Boolean> verifyHostname();

    /**
     * SSL context to use.
     */
    Optional<String> context();

    /**
     * Check if SSL is configured.
     */
    default boolean isConfigured() {
        return truststorePath().isPresent() || keystorePath().isPresent();
    }

    /**
     * Check if mutual TLS is configured (both truststore and keystore).
     */
    default boolean isMutualTlsConfigured() {
        return truststorePath().isPresent() && keystorePath().isPresent();
    }
}