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

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST Client interface for communicating with the LRA Coordinator.
 * This interface provides programmatic access to all LRA Coordinator endpoints
 * using JAX-RS client annotations.
 *
 * All methods correspond directly to the endpoints defined in
 * {@code io.narayana.lra.coordinator.api.Coordinator}.
 */
@Path(COORDINATOR_PATH_NAME)
public interface LRACoordinatorRestClient {

    /**
     * Get all LRAs known to the coordinator.
     * Corresponds to: GET /lra-coordinator/
     *
     * @param statusFilter Filter LRAs by status (optional)
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @return Response containing list of LRAs
     */
    @GET
    @Path("/")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response getAllLRAs(
            @QueryParam(STATUS_PARAM_NAME) @DefaultValue("") String statusFilter,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion);

    /**
     * Get the status of a specific LRA.
     * Corresponds to: GET /lra-coordinator/{LraId}/status
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @return Response containing LRA status
     */
    @GET
    @Path("{LraId}/status")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response getLRAStatus(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion);

    /**
     * Get detailed information about a specific LRA.
     * Corresponds to: GET /lra-coordinator/{LraId}
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @return Response containing LRA information
     */
    @GET
    @Path("{LraId}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response getLRAInfo(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion);

    /**
     * Start a new LRA.
     * Corresponds to: POST /lra-coordinator/start
     *
     * @param clientId Unique client identifier
     * @param timeLimit LRA timeout in milliseconds
     * @param parentLRA Parent LRA identifier (for nested LRAs)
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @return Response containing the new LRA identifier
     */
    @POST
    @Path("start")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response startLRA(
            @QueryParam(CLIENT_ID_PARAM_NAME) @DefaultValue("") String clientId,
            @QueryParam(TIMELIMIT_PARAM_NAME) @DefaultValue("0") Long timeLimit,
            @QueryParam(PARENT_LRA_PARAM_NAME) @DefaultValue("") String parentLRA,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion);

    /**
     * Update the time limit for an existing LRA.
     * Corresponds to: PUT /lra-coordinator/{LraId}/renew
     *
     * @param lraId The LRA identifier
     * @param timeLimit New time limit in milliseconds
     * @param apiVersion LRA API version
     * @return Response indicating success or failure
     */
    @PUT
    @Path("{LraId}/renew")
    Response renewTimeLimit(
            @PathParam("LraId") String lraId,
            @QueryParam(TIMELIMIT_PARAM_NAME) @DefaultValue("0") Long timeLimit,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion);

    /**
     * Close (complete) an LRA.
     * Corresponds to: PUT /lra-coordinator/{LraId}/close
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @param compensatorLink Compensator link header (optional)
     * @param userData User data header (optional)
     * @return Response containing the final LRA status
     */
    @PUT
    @Path("{LraId}/close")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response closeLRA(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion,
            @HeaderParam("Narayana-LRA-Participant-Link") @DefaultValue("") String compensatorLink,
            @HeaderParam(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME) @DefaultValue("") String userData);

    /**
     * Cancel (compensate) an LRA.
     * Corresponds to: PUT /lra-coordinator/{LraId}/cancel
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @param compensatorLink Compensator link header (optional)
     * @param userData User data header (optional)
     * @return Response containing the final LRA status
     */
    @PUT
    @Path("{LraId}/cancel")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response cancelLRA(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion,
            @HeaderParam("Narayana-LRA-Participant-Link") @DefaultValue("") String compensatorLink,
            @HeaderParam(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME) @DefaultValue("") String userData);

    /**
     * Join an LRA as a participant.
     * Corresponds to: PUT /lra-coordinator/{LraId}
     *
     * @param lraId The LRA identifier
     * @param timeLimit Participant timeout in milliseconds
     * @param compensatorLink Link header with compensator endpoints
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @param userData User data header (optional)
     * @param compensatorURL Compensator URL in request body (deprecated)
     * @return Response containing the recovery URL for the participant
     */
    @PUT
    @Path("{LraId}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response joinLRAViaBody(
            @PathParam("LraId") String lraId,
            @QueryParam(TIMELIMIT_PARAM_NAME) @DefaultValue("0") long timeLimit,
            @HeaderParam("Link") @DefaultValue("") String compensatorLink,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion,
            @HeaderParam(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME) @DefaultValue("") String userData,
            String compensatorURL);

    /**
     * Leave (remove participant from) an LRA.
     * Corresponds to: PUT /lra-coordinator/{LraId}/remove
     *
     * @param lraId The LRA identifier
     * @param acceptMediaType Response content type preference
     * @param apiVersion LRA API version
     * @param participantCompensatorUrl Participant compensator URL to remove
     * @return Response indicating success or failure
     */
    @PUT
    @Path("{LraId}/remove")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    Response leaveLRA(
            @PathParam("LraId") String lraId,
            @HeaderParam(HttpHeaders.ACCEPT) @DefaultValue(MediaType.TEXT_PLAIN) String acceptMediaType,
            @HeaderParam(NARAYANA_LRA_API_VERSION_HEADER_NAME) @DefaultValue(CURRENT_API_VERSION_STRING) String apiVersion,
            String participantCompensatorUrl);
}
