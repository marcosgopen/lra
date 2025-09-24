/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.narayana.lra.LRAData;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LRA Response Utils Tests")
class LRAResponseUtilsTest {

    @Test
    @DisplayName("Should extract LRA ID from JSON response")
    void shouldExtractLRAIdFromJsonResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(201);
        when(mockResponse.readEntity(String.class))
                .thenReturn("{\"lraId\":\"http://localhost:8080/lra-coordinator/test-123\"}");

        // When
        Optional<URI> lraId = LRAResponseUtils.extractLRAId(mockResponse);

        // Then
        assertThat(lraId).isPresent();
        assertThat(lraId.get().toString()).isEqualTo("http://localhost:8080/lra-coordinator/test-123");
    }

    @Test
    @DisplayName("Should extract LRA ID from plain text response")
    void shouldExtractLRAIdFromPlainTextResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(201);
        when(mockResponse.readEntity(String.class))
                .thenReturn("http://localhost:8080/lra-coordinator/test-456");

        // When
        Optional<URI> lraId = LRAResponseUtils.extractLRAId(mockResponse);

        // Then
        assertThat(lraId).isPresent();
        assertThat(lraId.get().toString()).isEqualTo("http://localhost:8080/lra-coordinator/test-456");
    }

    @Test
    @DisplayName("Should return empty when response is null")
    void shouldReturnEmptyWhenResponseIsNull() {
        // When
        Optional<URI> lraId = LRAResponseUtils.extractLRAId(null);

        // Then
        assertThat(lraId).isEmpty();
    }

    @Test
    @DisplayName("Should return empty when response is not successful")
    void shouldReturnEmptyWhenResponseIsNotSuccessful() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(404);

        // When
        Optional<URI> lraId = LRAResponseUtils.extractLRAId(mockResponse);

        // Then
        assertThat(lraId).isEmpty();
    }

    @Test
    @DisplayName("Should extract LRA status from JSON response")
    void shouldExtractLRAStatusFromJsonResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn("{\"status\":\"Active\"}");

        // When
        Optional<LRAStatus> status = LRAResponseUtils.extractLRAStatus(mockResponse);

        // Then
        assertThat(status).isPresent();
        assertThat(status.get()).isEqualTo(LRAStatus.Active);
    }

    @Test
    @DisplayName("Should extract LRA status from plain text response")
    void shouldExtractLRAStatusFromPlainTextResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class)).thenReturn("Closed");

        // When
        Optional<LRAStatus> status = LRAResponseUtils.extractLRAStatus(mockResponse);

        // Then
        assertThat(status).isPresent();
        assertThat(status.get()).isEqualTo(LRAStatus.Closed);
    }

    @Test
    @DisplayName("Should extract recovery URL from JSON response")
    void shouldExtractRecoveryUrlFromJsonResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn("{\"recoveryUrl\":\"http://localhost:8080/recovery/123\"}");

        // When
        Optional<URI> recoveryUrl = LRAResponseUtils.extractRecoveryUrl(mockResponse);

        // Then
        assertThat(recoveryUrl).isPresent();
        assertThat(recoveryUrl.get().toString()).isEqualTo("http://localhost:8080/recovery/123");
    }

    @Test
    @DisplayName("Should extract recovery URL from plain text response")
    void shouldExtractRecoveryUrlFromPlainTextResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn("http://localhost:8080/recovery/456");

        // When
        Optional<URI> recoveryUrl = LRAResponseUtils.extractRecoveryUrl(mockResponse);

        // Then
        assertThat(recoveryUrl).isPresent();
        assertThat(recoveryUrl.get().toString()).isEqualTo("http://localhost:8080/recovery/456");
    }

    @Test
    @DisplayName("Should extract LRA data from response")
    void shouldExtractLRADataFromResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn(
                        "{\"lraId\":\"http://localhost:8080/lra-coordinator/test-123\",\"clientId\":\"test-client\",\"status\":\"Active\",\"topLevel\":true,\"recovering\":false,\"startTime\":1640995200000,\"finishTime\":0,\"httpStatus\":200}");

        // When
        Optional<LRAData> lraData = LRAResponseUtils.extractLRAData(mockResponse);

        // Then
        assertThat(lraData).isPresent();
        assertThat(lraData.get().getLraId().toString()).isEqualTo("http://localhost:8080/lra-coordinator/test-123");
        assertThat(lraData.get().getClientId()).isEqualTo("test-client");
        assertThat(lraData.get().getStatus()).isEqualTo(LRAStatus.Active);
    }

    @Test
    @DisplayName("Should extract LRA data list from response")
    void shouldExtractLRADataListFromResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn(
                        "[{\"lraId\":\"http://localhost:8080/lra-coordinator/test-1\",\"clientId\":\"client-1\",\"status\":\"Active\",\"topLevel\":true,\"recovering\":false,\"startTime\":1640995200000,\"finishTime\":0,\"httpStatus\":200},{\"lraId\":\"http://localhost:8080/lra-coordinator/test-2\",\"clientId\":\"client-2\",\"status\":\"Closed\",\"topLevel\":true,\"recovering\":false,\"startTime\":1640995200000,\"finishTime\":1640995260000,\"httpStatus\":200}]");

        // When
        List<LRAData> lraDataList = LRAResponseUtils.extractLRADataList(mockResponse);

        // Then
        assertThat(lraDataList).hasSize(2);
        assertThat(lraDataList.get(0).getClientId()).isEqualTo("client-1");
        assertThat(lraDataList.get(0).getStatus()).isEqualTo(LRAStatus.Active);
        assertThat(lraDataList.get(1).getClientId()).isEqualTo("client-2");
        assertThat(lraDataList.get(1).getStatus()).isEqualTo(LRAStatus.Closed);
    }

    @Test
    @DisplayName("Should return empty list for non-array response")
    void shouldReturnEmptyListForNonArrayResponse() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn("{\"message\":\"not an array\"}");

        // When
        List<LRAData> lraDataList = LRAResponseUtils.extractLRADataList(mockResponse);

        // Then
        assertThat(lraDataList).isEmpty();
    }

    @Test
    @DisplayName("Should check if response is successful")
    void shouldCheckIfResponseIsSuccessful() {
        // Given
        Response successResponse = mock(Response.class);
        when(successResponse.getStatus()).thenReturn(200);

        Response createdResponse = mock(Response.class);
        when(createdResponse.getStatus()).thenReturn(201);

        Response errorResponse = mock(Response.class);
        when(errorResponse.getStatus()).thenReturn(404);

        Response serverErrorResponse = mock(Response.class);
        when(serverErrorResponse.getStatus()).thenReturn(500);

        // When & Then
        assertThat(LRAResponseUtils.isSuccessful(successResponse)).isTrue();
        assertThat(LRAResponseUtils.isSuccessful(createdResponse)).isTrue();
        assertThat(LRAResponseUtils.isSuccessful(errorResponse)).isFalse();
        assertThat(LRAResponseUtils.isSuccessful(serverErrorResponse)).isFalse();
        assertThat(LRAResponseUtils.isSuccessful(null)).isFalse();
    }

    @Test
    @DisplayName("Should get error message from response")
    void shouldGetErrorMessageFromResponse() {
        // Given
        Response errorResponseWithEntity = mock(Response.class);
        when(errorResponseWithEntity.getStatus()).thenReturn(404);
        when(errorResponseWithEntity.hasEntity()).thenReturn(true);
        when(errorResponseWithEntity.readEntity(String.class)).thenReturn("LRA not found");
        when(errorResponseWithEntity.getStatusInfo()).thenReturn(Response.Status.NOT_FOUND);

        Response errorResponseWithoutEntity = mock(Response.class);
        when(errorResponseWithoutEntity.getStatus()).thenReturn(500);
        when(errorResponseWithoutEntity.hasEntity()).thenReturn(false);
        when(errorResponseWithoutEntity.getStatusInfo()).thenReturn(Response.Status.INTERNAL_SERVER_ERROR);

        // When & Then
        assertThat(LRAResponseUtils.getErrorMessage(errorResponseWithEntity))
                .isEqualTo("LRA not found");

        assertThat(LRAResponseUtils.getErrorMessage(errorResponseWithoutEntity))
                .isEqualTo("HTTP 500: Internal Server Error");

        assertThat(LRAResponseUtils.getErrorMessage(null))
                .isEqualTo("No response received");
    }

    @Test
    @DisplayName("Should handle malformed JSON gracefully")
    void shouldHandleMalformedJsonGracefully() {
        // Given
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.readEntity(String.class))
                .thenReturn("{malformed json");

        // When
        Optional<LRAStatus> status = LRAResponseUtils.extractLRAStatus(mockResponse);
        Optional<URI> lraId = LRAResponseUtils.extractLRAId(mockResponse);
        Optional<LRAData> lraData = LRAResponseUtils.extractLRAData(mockResponse);
        List<LRAData> lraDataList = LRAResponseUtils.extractLRADataList(mockResponse);

        // Then
        assertThat(status).isEmpty();
        assertThat(lraId).isEmpty();
        assertThat(lraData).isEmpty();
        assertThat(lraDataList).isEmpty();
    }
}