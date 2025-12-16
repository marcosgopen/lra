/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import static io.narayana.lra.LRAConstants.*;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_RECOVERY_HEADER;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.api.LRACoordinatorService;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages participant enrollment and management for LRAs.
 * Extracted from NarayanaLRAClient to improve separation of concerns.
 */
public class ParticipantManager {

    private static final String LINK_TEXT = "Link";
    private final LRACoordinatorService coordinatorRestClient;

    public ParticipantManager(LRACoordinatorService coordinatorRestClient) {
        this.coordinatorRestClient = coordinatorRestClient;
    }

    /**
     * Enlist a compensator in an LRA with full endpoint specifications.
     *
     * @param lraUri LRA to join
     * @param timelimit participant timeout
     * @param compensateUri compensation endpoint
     * @param completeUri completion endpoint
     * @param forgetUri forget endpoint
     * @param leaveUri leave endpoint
     * @param afterUri after LRA endpoint
     * @param statusUri status endpoint
     * @param compensatorData participant data
     * @return recovery URL for the enrollment
     */
    public URI enlistCompensator(URI lraUri, Long timelimit,
            URI compensateUri, URI completeUri,
            URI forgetUri, URI leaveUri, URI afterUri, URI statusUri,
            StringBuilder compensatorData) {

        validateParticipantEndpoints(compensateUri, completeUri, forgetUri, leaveUri, afterUri, statusUri);

        Map<String, URI> terminateURIs = buildTerminationEndpoints(
                compensateUri, completeUri, forgetUri, leaveUri, afterUri, statusUri);

        StringBuilder linkHeaderValue = new StringBuilder();
        terminateURIs.forEach((k, v) -> makeLink(linkHeaderValue, null, k,
                v == null ? null : v.toASCIIString()));

        return enlistCompensator(lraUri, timelimit, linkHeaderValue.toString(), compensatorData);
    }

    /**
     * Enlist a compensator using a participant URI pattern.
     *
     * @param lraId LRA to join
     * @param timeLimit participant timeout
     * @param participantUri base participant URI
     * @param compensatorData participant data
     * @return recovery URL for the enrollment
     */
    public URI enlistParticipant(URI lraId, Long timeLimit,
            URI participantUri, StringBuilder compensatorData) {
        validateURI(participantUri, false, "Invalid participant URL: %s");

        StringBuilder linkHeaderValue = makeLink(new StringBuilder(), null,
                "participant", participantUri.toASCIIString());

        return enlistCompensator(lraId, timeLimit, linkHeaderValue.toString(), compensatorData);
    }

    /**
     * Remove a participant from an LRA.
     *
     * @param lraId LRA to leave
     * @param body optional leave body
     * @throws WebApplicationException if the operation fails
     */
    public void leaveLRA(URI lraId, String body) throws WebApplicationException {
        try {
            Response response = coordinatorRestClient.leaveLRA(
                    LRAConstants.getLRAUid(lraId),
                    MediaType.APPLICATION_JSON,
                    LRAConstants.CURRENT_API_VERSION_STRING,
                    body != null ? body : "");

            if (OK.getStatusCode() != response.getStatus()) {
                String logMsg = LRALogger.i18nLogger.error_lraLeaveUnexpectedStatus(lraId, response.getStatus(),
                        response.hasEntity() ? response.readEntity(String.class) : "");
                LRALogger.logger.error(logMsg);
                throw new WebApplicationException(Response.status(response.getStatus()).entity(logMsg).build());
            }
        } catch (Exception e) {
            throw new WebApplicationException(Response
                    .status(SERVICE_UNAVAILABLE)
                    .entity("leave LRA client request timed out, try again later").build());
        }
    }

    /**
     * Core method to enlist a compensator using link header format.
     */
    private URI enlistCompensator(URI uri, Long timelimit, String linkHeader, StringBuilder compensatorData) {
        validateLRAUri(uri);

        Long normalizedTimelimit = (timelimit == null || timelimit < 0) ? 0L : timelimit;
        String data = compensatorData == null ? null : compensatorData.toString();

        try {
            Response response = coordinatorRestClient.joinLRAViaBody(
                    LRAConstants.getLRAUid(uri),
                    normalizedTimelimit,
                    linkHeader != null ? linkHeader : "",
                    MediaType.APPLICATION_JSON,
                    LRAConstants.CURRENT_API_VERSION_STRING,
                    data != null ? data : "",
                    compensatorData == null ? linkHeader : data);

            return processEnrollmentResponse(uri, response, compensatorData);

        } catch (Exception e) {
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity("join LRA client request timed out, try again later").build());
        }
    }

    /**
     * Process the response from participant enrollment.
     */
    private URI processEnrollmentResponse(URI lraUri, Response response, StringBuilder compensatorData) {
        String responseEntity = response.hasEntity() ? response.readEntity(String.class) : "";

        switch (response.getStatus()) {
            case 412: // PRECONDITION_FAILED
                String logMsg = LRALogger.i18nLogger.error_tooLateToJoin(String.valueOf(lraUri), responseEntity);
                LRALogger.logger.error(logMsg);
                throw new WebApplicationException(logMsg,
                        Response.status(PRECONDITION_FAILED).entity(logMsg).build());

            case 404: // NOT_FOUND
                String notFoundMsg = LRALogger.i18nLogger.info_failedToEnlistingLRANotFound(
                        convertToURL(lraUri), lraUri, NOT_FOUND.getStatusCode(), NOT_FOUND.getReasonPhrase(),
                        GONE.getStatusCode(), GONE.getReasonPhrase());
                LRALogger.logger.info(notFoundMsg);
                throw new WebApplicationException(Response.status(GONE).entity(notFoundMsg).build());

            case 200: // OK
                return extractRecoveryUrl(response, lraUri, responseEntity, compensatorData);

            default:
                throw new WebApplicationException(responseEntity, response);
        }
    }

    /**
     * Extract recovery URL from enrollment response.
     */
    private URI extractRecoveryUrl(Response response, URI lraUri, String responseEntity, StringBuilder compensatorData) {
        String prevParticipantData = response.getHeaderString(NARAYANA_LRA_PARTICIPANT_DATA_HEADER_NAME);

        if (compensatorData != null && prevParticipantData != null) {
            compensatorData.setLength(0);
            compensatorData.append(prevParticipantData);
        }

        try {
            String recoveryUrl = response.getHeaderString(LRA_HTTP_RECOVERY_HEADER);
            return new URI(recoveryUrl);
        } catch (URISyntaxException e) {
            String errorMsg = String.format("join %s returned an invalid recovery URI: %s", lraUri, responseEntity);
            LRALogger.logger.infof(e, errorMsg);
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE).entity(errorMsg).build());
        }
    }

    /**
     * Build termination endpoints map.
     */
    private Map<String, URI> buildTerminationEndpoints(URI compensateUri, URI completeUri,
            URI forgetUri, URI leaveUri,
            URI afterUri, URI statusUri) {
        Map<String, URI> terminateURIs = new HashMap<>();
        terminateURIs.put(COMPENSATE, compensateUri);
        terminateURIs.put(COMPLETE, completeUri);
        terminateURIs.put(LEAVE, leaveUri);
        terminateURIs.put(AFTER, afterUri);
        terminateURIs.put(STATUS, statusUri);
        terminateURIs.put(FORGET, forgetUri);
        return terminateURIs;
    }

    /**
     * Validate participant endpoints.
     */
    private void validateParticipantEndpoints(URI compensateUri, URI completeUri, URI forgetUri,
            URI leaveUri, URI afterUri, URI statusUri) {
        validateURI(completeUri, true, "Invalid complete URL: %s");
        validateURI(compensateUri, true, "Invalid compensate URL: %s");
        validateURI(leaveUri, true, "Invalid leave URL: %s");
        validateURI(afterUri, true, "Invalid after URL: %s");
        validateURI(forgetUri, true, "Invalid forgetUri URL: %s");
        validateURI(statusUri, true, "Invalid status URL: %s");
    }

    /**
     * Validate LRA URI.
     */
    private void validateLRAUri(URI uri) {
        try {
            uri.toURL();
        } catch (MalformedURLException mue) {
            throw new WebApplicationException("Could not convert LRA to a URL : " + mue.getMessage(),
                    Response.status(INTERNAL_SERVER_ERROR).build());
        }
    }

    /**
     * Validate a URI parameter.
     */
    private void validateURI(URI uri, boolean nullAllowed, String message) {
        if (uri == null) {
            if (!nullAllowed) {
                throw new WebApplicationException(String.format(message, "null value"),
                        Response.status(NOT_ACCEPTABLE).build());
            }
        } else {
            try {
                uri.toURL();
            } catch (MalformedURLException mue) {
                String errorMsg = String.format(message, mue.getMessage()) + " uri=" + uri;
                throw new WebApplicationException(errorMsg, Response.status(NOT_ACCEPTABLE).build());
            }
        }
    }

    /**
     * Convert URI to URL safely.
     */
    private URL convertToURL(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid URI: " + uri, e);
        }
    }

    /**
     * Make a Link header value.
     */
    private static StringBuilder makeLink(StringBuilder b, String uriPrefix, String key, String value) {
        if (value == null) {
            return b;
        }

        String terminationUri = uriPrefix == null ? value : String.format("%s%s", uriPrefix, value);
        Link link = Link.fromUri(terminationUri).title(key + " URI").rel(key).type(MediaType.TEXT_PLAIN).build();

        if (b.length() != 0) {
            b.append(',');
        }

        return b.append(link);
    }
}
