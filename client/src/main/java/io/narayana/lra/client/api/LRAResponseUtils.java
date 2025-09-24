/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.narayana.lra.LRAData;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

/**
 * Utility class for processing LRA Coordinator REST responses.
 * Provides convenience methods for extracting LRA data from HTTP responses.
 */
public class LRAResponseUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LRAResponseUtils() {
        // Utility class
    }

    /**
     * Extract LRA ID from a start LRA response.
     *
     * @param response The response from startLRA()
     * @return The LRA ID as a URI, or empty if not found or invalid
     */
    public static Optional<URI> extractLRAId(Response response) {
        if (response == null || !isSuccessful(response)) {
            return Optional.empty();
        }

        try {
            String responseBody = response.readEntity(String.class);

            // Try to parse as JSON first
            if (responseBody.trim().startsWith("{")) {
                JsonNode jsonNode = OBJECT_MAPPER.readTree(responseBody);
                if (jsonNode.has("lraId")) {
                    return Optional.of(new URI(jsonNode.get("lraId").asText()));
                }
            } else {
                // Try to parse as plain text URI
                return Optional.of(new URI(responseBody.trim()));
            }
        } catch (Exception e) {
            // Log the error in a real implementation
        }

        return Optional.empty();
    }

    /**
     * Extract LRA status from a response.
     *
     * @param response The response from getLRAStatus(), closeLRA(), etc.
     * @return The LRA status, or empty if not found or invalid
     */
    public static Optional<LRAStatus> extractLRAStatus(Response response) {
        if (response == null || !isSuccessful(response)) {
            return Optional.empty();
        }

        try {
            String responseBody = response.readEntity(String.class);

            // Try to parse as JSON first
            if (responseBody.trim().startsWith("{")) {
                JsonNode jsonNode = OBJECT_MAPPER.readTree(responseBody);
                if (jsonNode.has("status")) {
                    return Optional.of(LRAStatus.valueOf(jsonNode.get("status").asText()));
                }
            } else {
                // Try to parse as plain text status
                return Optional.of(LRAStatus.valueOf(responseBody.trim()));
            }
        } catch (Exception e) {
            // Log the error in a real implementation
        }

        return Optional.empty();
    }

    /**
     * Extract LRA data from a getLRAInfo response.
     *
     * @param response The response from getLRAInfo()
     * @return The LRA data, or empty if not found or invalid
     */
    public static Optional<LRAData> extractLRAData(Response response) {
        if (response == null || !isSuccessful(response)) {
            return Optional.empty();
        }

        try {
            String responseBody = response.readEntity(String.class);
            LRAData lraData = OBJECT_MAPPER.readValue(responseBody, LRAData.class);
            return Optional.of(lraData);
        } catch (Exception e) {
            // Log the error in a real implementation
        }

        return Optional.empty();
    }

    /**
     * Extract list of LRA data from a getAllLRAs response.
     *
     * @param response The response from getAllLRAs()
     * @return The list of LRA data, or empty list if not found or invalid
     */
    public static List<LRAData> extractLRADataList(Response response) {
        if (response == null || !isSuccessful(response)) {
            return List.of();
        }

        try {
            String responseBody = response.readEntity(String.class);

            // Try to parse as JSON array
            if (responseBody.trim().startsWith("[")) {
                return OBJECT_MAPPER.readValue(responseBody, new TypeReference<List<LRAData>>() {
                });
            }
        } catch (Exception e) {
            // Log the error in a real implementation
        }

        return List.of();
    }

    /**
     * Extract recovery URL from a join LRA response.
     *
     * @param response The response from joinLRA()
     * @return The recovery URL as a URI, or empty if not found or invalid
     */
    public static Optional<URI> extractRecoveryUrl(Response response) {
        if (response == null || !isSuccessful(response)) {
            return Optional.empty();
        }

        try {
            String responseBody = response.readEntity(String.class);

            // Try to parse as JSON first
            if (responseBody.trim().startsWith("{")) {
                JsonNode jsonNode = OBJECT_MAPPER.readTree(responseBody);
                if (jsonNode.has("recoveryUrl")) {
                    return Optional.of(new URI(jsonNode.get("recoveryUrl").asText()));
                }
            } else {
                // Try to parse as plain text URI
                return Optional.of(new URI(responseBody.trim()));
            }
        } catch (Exception e) {
            // Log the error in a real implementation
        }

        return Optional.empty();
    }

    /**
     * Check if the HTTP response indicates success.
     *
     * @param response The HTTP response
     * @return true if the response indicates success (2xx status code)
     */
    public static boolean isSuccessful(Response response) {
        return response != null && response.getStatus() >= 200 && response.getStatus() < 300;
    }

    /**
     * Get error message from a failed response.
     *
     * @param response The HTTP response
     * @return The error message, or a default message if not available
     */
    public static String getErrorMessage(Response response) {
        if (response == null) {
            return "No response received";
        }

        try {
            if (response.hasEntity()) {
                return response.readEntity(String.class);
            }
        } catch (Exception e) {
            // Log the error in a real implementation
        }

        return String.format("HTTP %d: %s", response.getStatus(), response.getStatusInfo().getReasonPhrase());
    }
}
