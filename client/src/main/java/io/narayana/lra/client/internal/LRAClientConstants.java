/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

/**
 * Centralized constants for the LRA client implementation.
 */
public final class LRAClientConstants {

    private LRAClientConstants() {
        // Utility class - prevent instantiation
    }

    // Configuration property keys
    public static final String LRA_COORDINATOR_URL_KEY = "lra.coordinator.url";
    public static final String COORDINATOR_LB_METHOD_KEY = "lra.coordinator.lb-method";

    // SSL Configuration keys
    public static final String SSL_TRUSTSTORE_PATH_KEY = "lra.coordinator.ssl.truststore.path";
    public static final String SSL_TRUSTSTORE_PASSWORD_KEY = "lra.coordinator.ssl.truststore.password";
    public static final String SSL_TRUSTSTORE_TYPE_KEY = "lra.coordinator.ssl.truststore.type";
    public static final String SSL_KEYSTORE_PATH_KEY = "lra.coordinator.ssl.keystore.path";
    public static final String SSL_KEYSTORE_PASSWORD_KEY = "lra.coordinator.ssl.keystore.password";
    public static final String SSL_KEYSTORE_TYPE_KEY = "lra.coordinator.ssl.keystore.type";
    public static final String SSL_VERIFY_HOSTNAME_KEY = "lra.coordinator.ssl.verify-hostname";
    public static final String SSL_CONTEXT_KEY = "lra.coordinator.ssl.context";

    // JWT Configuration keys
    public static final String JWT_ENABLED_PROPERTY = "lra.coordinator.jwt.enabled";
    public static final String JWT_TOKEN_PROPERTY = "lra.coordinator.jwt.token";
    public static final String JWT_HEADER_PROPERTY = "lra.coordinator.jwt.header";
    public static final String JWT_PREFIX_PROPERTY = "lra.coordinator.jwt.prefix";

    // Load balancing algorithms
    public static final String LB_METHOD_ROUND_ROBIN = "round-robin";
    public static final String LB_METHOD_STICKY = "sticky";
    public static final String LB_METHOD_RANDOM = "random";
    public static final String LB_METHOD_LEAST_REQUESTS = "least-requests";
    public static final String LB_METHOD_LEAST_RESPONSE_TIME = "least-response-time";
    public static final String LB_METHOD_POWER_OF_TWO_CHOICES = "power-of-two-choices";
    public static final String[] NARAYANA_LRA_SUPPORTED_LB_METHODS = new String[] {
            LB_METHOD_ROUND_ROBIN,
            LB_METHOD_STICKY,
            LB_METHOD_RANDOM,
            LB_METHOD_LEAST_REQUESTS,
            LB_METHOD_LEAST_RESPONSE_TIME,
            LB_METHOD_POWER_OF_TWO_CHOICES
    };

    // Timeout values (in seconds)
    public static final long DEFAULT_CLIENT_TIMEOUT = Long.getLong("lra.internal.client.timeout", 10);
    public static final long START_TIMEOUT = Long.getLong("lra.internal.client.timeout.start", DEFAULT_CLIENT_TIMEOUT);
    public static final long JOIN_TIMEOUT = Long.getLong("lra.internal.client.timeout.join", DEFAULT_CLIENT_TIMEOUT);
    public static final long END_TIMEOUT = Long.getLong("lra.internal.client.end.timeout", DEFAULT_CLIENT_TIMEOUT);
    public static final long LEAVE_TIMEOUT = Long.getLong("lra.internal.client.leave.timeout", DEFAULT_CLIENT_TIMEOUT);
    public static final long QUERY_TIMEOUT = Long.getLong("lra.internal.client.query.timeout", DEFAULT_CLIENT_TIMEOUT);

    // Default values
    public static final String DEFAULT_COORDINATOR_URL = "http://localhost:8080/lra-coordinator";
    public static final String DEFAULT_KEYSTORE_TYPE = "JKS";
    public static final String DEFAULT_JWT_HEADER = "Authorization";
    public static final String DEFAULT_JWT_PREFIX = "Bearer ";
    public static final String DEFAULT_SSL_PROTOCOL = "TLS";

    // HTTP-related constants
    public static final String LINK_TEXT = "Link";
}