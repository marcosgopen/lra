/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import static io.narayana.lra.LRAConstants.CLIENT_ID_PARAM_NAME;
import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static io.narayana.lra.LRAConstants.CURRENT_API_VERSION_STRING;
import static io.narayana.lra.LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME;
import static io.narayana.lra.LRAConstants.NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME;
import static io.narayana.lra.LRAConstants.PARENT_LRA_PARAM_NAME;
import static io.narayana.lra.LRAConstants.STATUS_PARAM_NAME;
import static io.narayana.lra.LRAConstants.TIMELIMIT_PARAM_NAME;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

/**
 * Concrete implementation of LRA Coordinator REST Client.
 * This class provides a programmatic API for communicating with the LRA Coordinator
 * using JAX-RS Client API.
 */
public class LRACoordinatorClient implements AutoCloseable {

    private final Client client;
    private final String coordinatorBaseUrl;
    private final String apiVersion;

    // Default timeout for requests in seconds
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * Creates a new LRA Coordinator Client.
     *
     * @param coordinatorBaseUrl The base URL of the LRA Coordinator service
     */
    public LRACoordinatorClient(String coordinatorBaseUrl) {
        this(coordinatorBaseUrl, CURRENT_API_VERSION_STRING);
    }

    /**
     * Creates a new LRA Coordinator Client with specific API version.
     *
     * @param coordinatorBaseUrl The base URL of the LRA Coordinator service
     * @param apiVersion The LRA API version to use
     */
    public LRACoordinatorClient(String coordinatorBaseUrl, String apiVersion) {
        this.client = ClientBuilder.newClient();
        this.coordinatorBaseUrl = coordinatorBaseUrl.endsWith("/")
                ? coordinatorBaseUrl.substring(0, coordinatorBaseUrl.length() - 1)
                : coordinatorBaseUrl;
        this.apiVersion = apiVersion;
    }

    /**
     * Get all LRAs known to the coordinator.
     *
     * @return Response containing list of LRAs
     */
    public Response getAllLRAs() {
        return getAllLRAs(null, MediaType.APPLICATION_JSON);
    }

    /**
     * Get all LRAs known to the coordinator filtered by status.
     *
     * @param statusFilter Filter LRAs by status (optional)
     * @param acceptMediaType Response content type preference
     * @return Response containing list of LRAs
     */
    public Response getAllLRAs(LRAStatus statusFilter, String acceptMediaType) {
        WebTarget target = client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME);

        if (statusFilter != null) {
            target = target.queryParam(STATUS_PARAM_NAME, statusFilter.name());
        }

        return target.request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .get();
    }

    /**
     * Get the status of a specific LRA.
     *
     * @param lraId The LRA identifier
     * @return Response containing LRA status
     */
    public Response getLRAStatus(URI lraId) {
        return getLRAStatus(lraId, MediaType.APPLICATION_JSON);
    }

    /**
     * Get the status of a specific LRA.
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @return Response containing LRA status
     */
    public Response getLRAStatus(URI lraId, String acceptMediaType) {
        return client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId))
                .path("status")
                .request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .get();
    }

    /**
     * Get detailed information about a specific LRA.
     *
     * @param lraId The LRA identifier
     * @return Response containing LRA information
     */
    public Response getLRAInfo(URI lraId) {
        return getLRAInfo(lraId, MediaType.APPLICATION_JSON);
    }

    /**
     * Get detailed information about a specific LRA.
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @return Response containing LRA information
     */
    public Response getLRAInfo(URI lraId, String acceptMediaType) {
        return client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId))
                .request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .get();
    }

    /**
     * Start a new LRA.
     *
     * @param clientId Unique client identifier
     * @return Response containing the new LRA identifier
     */
    public Response startLRA(String clientId) {
        return startLRA(clientId, 0L, null, MediaType.APPLICATION_JSON);
    }

    /**
     * Start a new LRA with timeout.
     *
     * @param clientId Unique client identifier
     * @param timeLimit LRA timeout in milliseconds
     * @return Response containing the new LRA identifier
     */
    public Response startLRA(String clientId, Long timeLimit) {
        return startLRA(clientId, timeLimit, null, MediaType.APPLICATION_JSON);
    }

    /**
     * Start a new LRA.
     *
     * @param clientId Unique client identifier
     * @param timeLimit LRA timeout in milliseconds
     * @param parentLRA Parent LRA identifier (for nested LRAs)
     * @param acceptMediaType Response content type preference
     * @return Response containing the new LRA identifier
     */
    public Response startLRA(String clientId, Long timeLimit, URI parentLRA, String acceptMediaType) {
        WebTarget target = client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path("start")
                .queryParam(CLIENT_ID_PARAM_NAME, clientId);

        if (timeLimit != null && timeLimit > 0) {
            target = target.queryParam(TIMELIMIT_PARAM_NAME, timeLimit);
        }

        if (parentLRA != null) {
            target = target.queryParam(PARENT_LRA_PARAM_NAME, parentLRA.toString());
        }

        return target.request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .post(Entity.text(""));
    }

    /**
     * Update the time limit for an existing LRA.
     *
     * @param lraId The LRA identifier
     * @param timeLimit New time limit in milliseconds
     * @return Response indicating success or failure
     */
    public Response renewTimeLimit(URI lraId, Long timeLimit) {
        return client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId))
                .path("renew")
                .queryParam(TIMELIMIT_PARAM_NAME, timeLimit)
                .request()
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .put(Entity.text(""));
    }

    /**
     * Close (complete) an LRA.
     *
     * @param lraId The LRA identifier
     * @return Response containing the final LRA status
     */
    public Response closeLRA(URI lraId) {
        return closeLRA(lraId, MediaType.APPLICATION_JSON, null, null);
    }

    /**
     * Close (complete) an LRA.
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param compensatorLink Compensator link header (optional)
     * @param userData User data header (optional)
     * @return Response containing the final LRA status
     */
    public Response closeLRA(URI lraId, String acceptMediaType, String compensatorLink, String userData) {
        var requestBuilder = client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId))
                .path("close")
                .request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion);

        if (compensatorLink != null && !compensatorLink.trim().isEmpty()) {
            requestBuilder = requestBuilder.header("Narayana-LRA-Participant-Link", compensatorLink);
        }

        if (userData != null && !userData.trim().isEmpty()) {
            requestBuilder = requestBuilder.header(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME, userData);
        }

        return requestBuilder.put(Entity.text(""));
    }

    /**
     * Cancel (compensate) an LRA.
     *
     * @param lraId The LRA identifier
     * @return Response containing the final LRA status
     */
    public Response cancelLRA(URI lraId) {
        return cancelLRA(lraId, MediaType.APPLICATION_JSON, null, null);
    }

    /**
     * Cancel (compensate) an LRA.
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param compensatorLink Compensator link header (optional)
     * @param userData User data header (optional)
     * @return Response containing the final LRA status
     */
    public Response cancelLRA(URI lraId, String acceptMediaType, String compensatorLink, String userData) {
        var requestBuilder = client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId))
                .path("cancel")
                .request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion);

        if (compensatorLink != null && !compensatorLink.trim().isEmpty()) {
            requestBuilder = requestBuilder.header("Narayana-LRA-Participant-Link", compensatorLink);
        }

        if (userData != null && !userData.trim().isEmpty()) {
            requestBuilder = requestBuilder.header(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME, userData);
        }

        return requestBuilder.put(Entity.text(""));
    }

    /**
     * Join an LRA as a participant.
     *
     * @param lraId The LRA identifier
     * @param compensatorLink Link header with compensator endpoints
     * @return Response containing the recovery URL for the participant
     */
    public Response joinLRA(URI lraId, String compensatorLink) {
        return joinLRA(lraId, 0L, compensatorLink, MediaType.APPLICATION_JSON, null, null);
    }

    /**
     * Join an LRA as a participant.
     *
     * @param lraId The LRA identifier
     * @param timeLimit Participant timeout in milliseconds
     * @param compensatorLink Link header with compensator endpoints
     * @param acceptMediaType Response content type preference
     * @param userData User data header (optional)
     * @param compensatorURL Compensator URL in request body (deprecated)
     * @return Response containing the recovery URL for the participant
     */
    public Response joinLRA(URI lraId, long timeLimit, String compensatorLink,
            String acceptMediaType, String userData, String compensatorURL) {
        WebTarget target = client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId));

        if (timeLimit > 0) {
            target = target.queryParam(TIMELIMIT_PARAM_NAME, timeLimit);
        }

        var requestBuilder = target.request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion);

        if (compensatorLink != null && !compensatorLink.trim().isEmpty()) {
            requestBuilder = requestBuilder.header("Link", compensatorLink);
        }

        if (userData != null && !userData.trim().isEmpty()) {
            requestBuilder = requestBuilder.header(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME, userData);
        }

        String body = (compensatorURL != null) ? compensatorURL : "";
        return requestBuilder.put(Entity.text(body));
    }

    /**
     * Leave (remove participant from) an LRA.
     *
     * @param lraId The LRA identifier
     * @param participantCompensatorUrl Participant compensator URL to remove
     * @return Response indicating success or failure
     */
    public Response leaveLRA(URI lraId, String participantCompensatorUrl) {
        return leaveLRA(lraId, MediaType.APPLICATION_JSON, participantCompensatorUrl);
    }

    /**
     * Leave (remove participant from) an LRA.
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param participantCompensatorUrl Participant compensator URL to remove
     * @return Response indicating success or failure
     */
    public Response leaveLRA(URI lraId, String acceptMediaType, String participantCompensatorUrl) {
        String body = (participantCompensatorUrl != null) ? participantCompensatorUrl : "";

        return client.target(coordinatorBaseUrl)
                .path(COORDINATOR_PATH_NAME)
                .path(extractLRAId(lraId))
                .path("remove")
                .request(acceptMediaType)
                .header(NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .put(Entity.text(body));
    }

    /**
     * Extract LRA ID from URI for path parameter usage.
     * If the URI is a full LRA URL, extract just the ID part.
     * Otherwise, use the URI as-is.
     */
    private String extractLRAId(URI lraId) {
        String lraIdStr = lraId.toString();

        // If it's a full LRA URL, extract the ID part after the last '/'
        if (lraIdStr.contains(COORDINATOR_PATH_NAME)) {
            int lastSlash = lraIdStr.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < lraIdStr.length() - 1) {
                return lraIdStr.substring(lastSlash + 1);
            }
        }

        return lraIdStr;
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
