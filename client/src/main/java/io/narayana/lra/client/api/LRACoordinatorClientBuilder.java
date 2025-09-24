/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import static io.narayana.lra.LRAConstants.CURRENT_API_VERSION_STRING;

import java.util.concurrent.TimeUnit;

/**
 * Builder class for creating LRA Coordinator Client instances with custom configuration.
 */
public class LRACoordinatorClientBuilder {

    private String coordinatorBaseUrl;
    private String apiVersion = CURRENT_API_VERSION_STRING;
    private long connectTimeout = 30; // seconds
    private long readTimeout = 60; // seconds
    private TimeUnit timeUnit = TimeUnit.SECONDS;

    private LRACoordinatorClientBuilder() {
    }

    /**
     * Create a new builder instance.
     *
     * @return A new LRACoordinatorClientBuilder
     */
    public static LRACoordinatorClientBuilder newBuilder() {
        return new LRACoordinatorClientBuilder();
    }

    /**
     * Set the coordinator base URL.
     *
     * @param coordinatorBaseUrl The base URL of the LRA Coordinator service
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder coordinatorUrl(String coordinatorBaseUrl) {
        this.coordinatorBaseUrl = coordinatorBaseUrl;
        return this;
    }

    /**
     * Set the LRA API version.
     *
     * @param apiVersion The LRA API version to use
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder apiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
        return this;
    }

    /**
     * Set the connection timeout.
     *
     * @param timeout The timeout value
     * @param unit The time unit for the timeout
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        this.connectTimeout = timeout;
        this.timeUnit = unit;
        return this;
    }

    /**
     * Set the read timeout.
     *
     * @param timeout The timeout value
     * @param unit The time unit for the timeout
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder readTimeout(long timeout, TimeUnit unit) {
        this.readTimeout = timeout;
        this.timeUnit = unit;
        return this;
    }

    /**
     * Build the LRA Coordinator Client.
     *
     * @return A configured LRACoordinatorClient instance
     * @throws IllegalStateException if coordinatorUrl is not set
     */
    public LRACoordinatorClient build() {
        if (coordinatorBaseUrl == null || coordinatorBaseUrl.trim().isEmpty()) {
            throw new IllegalStateException("Coordinator base URL must be specified");
        }

        return new LRACoordinatorClient(coordinatorBaseUrl, apiVersion);
    }
}
