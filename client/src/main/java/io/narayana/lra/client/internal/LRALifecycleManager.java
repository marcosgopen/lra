/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import static io.narayana.lra.client.internal.LRAClientConstants.*;
import static jakarta.ws.rs.core.Response.Status.*;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.api.LRACoordinatorService;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Manages the lifecycle operations for LRAs including starting, ending, and status management.
 * Extracted from NarayanaLRAClient to improve separation of concerns.
 */
public class LRALifecycleManager {

    private final LRACoordinatorService coordinatorRestClient;
    private final LoadBalancingManager loadBalancingManager;

    public LRALifecycleManager(LRACoordinatorService coordinatorRestClient, LoadBalancingManager loadBalancingManager) {
        this.coordinatorRestClient = coordinatorRestClient;
        this.loadBalancingManager = loadBalancingManager;
    }

    /**
     * Start a new LRA with the given parameters.
     *
     * @param parentLRA parent LRA for nesting (null for top-level)
     * @param clientID client identifier
     * @param timeout timeout value
     * @param unit timeout unit
     * @param verbose whether to provide verbose error logging
     * @return the URI of the created LRA
     * @throws WebApplicationException if LRA creation fails
     */
    public URI startLRA(URI parentLRA, String clientID, Long timeout, ChronoUnit unit, boolean verbose)
            throws WebApplicationException {

        validateStartLRAParameters(clientID, timeout, unit);

        String encodedParentLRA = parentLRA == null ? ""
                : URLEncoder.encode(parentLRA.toString(), StandardCharsets.UTF_8);

        if (loadBalancingManager != null && loadBalancingManager.isLoadBalancing()) {
            return startLRAWithLoadBalancing(clientID, timeout, unit, encodedParentLRA, verbose);
        } else {
            return startLRASingleCoordinator(clientID, timeout, unit, encodedParentLRA, verbose);
        }
    }

    /**
     * End an LRA (either close or cancel).
     *
     * @param lra LRA to end
     * @param confirm true to close, false to cancel
     * @param compensator optional compensator URL
     * @param userData optional user data
     * @throws WebApplicationException if the operation fails
     */
    public void endLRA(URI lra, boolean confirm, String compensator, String userData) throws WebApplicationException {
        LRALogger.logger.tracef("%s LRA: %s", confirm ? "close" : "compensate", lra);

        try {
            Response response = callEndLRAEndpoint(lra, confirm, compensator, userData);
            validateEndLRAResponse(response);
        } catch (Exception e) {
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity("end LRA client request failed: " + e.getMessage()).build());
        }
    }

    /**
     * Validate parameters for starting an LRA.
     */
    private void validateStartLRAParameters(String clientID, Long timeout, ChronoUnit unit) {
        if (loadBalancingManager != null && loadBalancingManager.isClustered() &&
                !loadBalancingManager.isLoadBalancing()) {
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity("Unsupported load balancer configuration").build());
        }

        if (timeout != null && timeout < 0) {
            String errorMsg = LRALogger.i18nLogger.warn_invalid_timeout(timeout);
            throw new WebApplicationException(Response.status(BAD_REQUEST).entity(errorMsg).build());
        }
    }

    /**
     * Start LRA with load balancing across multiple coordinators.
     */
    private URI startLRAWithLoadBalancing(String clientID, Long timeout, ChronoUnit unit,
            String encodedParentLRA, boolean verbose) {

        int coordinatorCount = loadBalancingManager.getCoordinatorCount();
        boolean supportsFailover = loadBalancingManager.supportsFailover();

        for (int i = 0; i < coordinatorCount; i++) {
            try {
                var instance = loadBalancingManager.getCoordinatorService()
                        .selectInstance()
                        .await().atMost(Duration.ofSeconds(START_TIMEOUT));

                if (LRALogger.logger.isDebugEnabled()) {
                    LRALogger.logger.debugf("Selected coordinator %s:%d", instance.getHost(), instance.getPort());
                }

                URI coordinatorInstance = UriBuilder.fromPath("/lra-coordinator")
                        .scheme(instance.isSecure() ? "https" : "http")
                        .host(instance.getHost())
                        .port(instance.getPort()).build();

                return executeStartLRA(clientID, timeout, unit, encodedParentLRA);

            } catch (Exception e) {
                if (supportsFailover && i == coordinatorCount - 1) {
                    String errMsg = LRALogger.i18nLogger.warn_startLRAFailed(e.getMessage());
                    LRALogger.logger.warn(errMsg, e);
                    throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE).entity(errMsg).build());
                }
                // Continue to next coordinator for failover
            }
        }

        throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                .entity("no available coordinator").build());
    }

    /**
     * Start LRA with a single coordinator.
     */
    private URI startLRASingleCoordinator(String clientID, Long timeout, ChronoUnit unit,
            String encodedParentLRA, boolean verbose) {
        try {
            return executeStartLRA(clientID, timeout, unit, encodedParentLRA);
        } catch (Exception e) {
            String errMsg = LRALogger.i18nLogger.warn_startLRAFailed(e.getMessage());
            LRALogger.logger.warn(errMsg, e);
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE).entity(errMsg).build());
        }
    }

    /**
     * Execute the actual LRA start call to coordinator.
     */
    private URI executeStartLRA(String clientID, Long timeout, ChronoUnit unit, String encodedParentLRA) {
        String normalizedClientID = clientID == null ? "" : clientID;
        Long normalizedTimeout = timeout == null ? 0L : timeout;
        ChronoUnit normalizedUnit = unit == null ? ChronoUnit.SECONDS : unit;

        Response response = coordinatorRestClient.startLRA(
                normalizedClientID,
                Duration.of(normalizedTimeout, normalizedUnit).toMillis(),
                encodedParentLRA,
                MediaType.APPLICATION_JSON,
                LRAConstants.CURRENT_API_VERSION_STRING);

        return processStartLRAResponse(response);
    }

    /**
     * Process the response from LRA start operation.
     */
    private URI processStartLRAResponse(Response response) {
        if (response.getStatus() != CREATED.getStatusCode()) {
            LRALogger.logger.error(
                    LRALogger.i18nLogger.error_lraCreationUnexpectedStatus(response.getStatus(), ""));
            throw new WebApplicationException(response);
        }

        URI lra = URI.create(response.getHeaderString(HttpHeaders.LOCATION));
        LRALogger.logger.tracef("startLRA returned: %s", lra);

        return lra;
    }

    /**
     * Call the appropriate endpoint for ending an LRA.
     */
    private Response callEndLRAEndpoint(URI lra, boolean confirm, String compensator, String userData) {
        String normalizedCompensator = compensator != null ? compensator : "";
        String normalizedUserData = userData != null ? userData : "";

        if (confirm) {
            return coordinatorRestClient.closeLRA(
                    LRAConstants.getLRAUid(lra),
                    MediaType.APPLICATION_JSON,
                    LRAConstants.CURRENT_API_VERSION_STRING,
                    normalizedCompensator,
                    normalizedUserData);
        } else {
            return coordinatorRestClient.cancelLRA(
                    LRAConstants.getLRAUid(lra),
                    MediaType.APPLICATION_JSON,
                    LRAConstants.CURRENT_API_VERSION_STRING,
                    normalizedCompensator,
                    normalizedUserData);
        }
    }

    /**
     * Validate the response from end LRA operation.
     */
    private void validateEndLRAResponse(Response response) {
        if (response.getStatus() != OK.getStatusCode() &&
                response.getStatus() != ACCEPTED.getStatusCode()) {
            throw new WebApplicationException(response);
        }
    }
}
