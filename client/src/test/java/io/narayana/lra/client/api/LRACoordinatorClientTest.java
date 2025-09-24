/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LRA Coordinator Client Tests")
class LRACoordinatorClientTest {

    @Mock
    private Client mockClient;

    @Mock
    private WebTarget mockWebTarget;

    @Mock
    private Invocation.Builder mockRequestBuilder;

    @Mock
    private Response mockResponse;

    private LRACoordinatorClient client;
    private MockedStatic<ClientBuilder> clientBuilderMock;

    private static final String COORDINATOR_URL = "http://localhost:8080/lra-coordinator/";
    private static final String TEST_LRA_ID = "test-lra-123";
    private static final URI TEST_LRA_URI = URI.create("http://localhost:8080/lra-coordinator/" + TEST_LRA_ID);

    @BeforeEach
    void setUp() {
        clientBuilderMock = Mockito.mockStatic(ClientBuilder.class);
        clientBuilderMock.when(ClientBuilder::newClient).thenReturn(mockClient);

        // Set up the basic JAX-RS client chain that all tests need using lenient stubs
        lenient().when(mockClient.target(anyString())).thenReturn(mockWebTarget);
        lenient().when(mockWebTarget.path(anyString())).thenReturn(mockWebTarget);
        lenient().when(mockWebTarget.queryParam(anyString(), any())).thenReturn(mockWebTarget);
        lenient().when(mockWebTarget.request()).thenReturn(mockRequestBuilder);
        lenient().when(mockWebTarget.request(anyString())).thenReturn(mockRequestBuilder);
        lenient().when(mockRequestBuilder.header(anyString(), any())).thenReturn(mockRequestBuilder);
        lenient().when(mockRequestBuilder.get()).thenReturn(mockResponse);
        lenient().when(mockRequestBuilder.post(any())).thenReturn(mockResponse);
        lenient().when(mockRequestBuilder.put(any())).thenReturn(mockResponse);

        // Note: Individual tests will set up specific response behaviors as needed

        client = new LRACoordinatorClient(COORDINATOR_URL);
    }

    @AfterEach
    void tearDown() {
        clientBuilderMock.close();
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Should create client with coordinator URL")
    void shouldCreateClientWithCoordinatorUrl() {
        LRACoordinatorClient testClient = new LRACoordinatorClient(COORDINATOR_URL);
        assertThat(testClient).isNotNull();
        testClient.close();
    }

    @Test
    @DisplayName("Should create client with custom API version")
    void shouldCreateClientWithCustomApiVersion() {
        String customVersion = "2.0";
        LRACoordinatorClient testClient = new LRACoordinatorClient(COORDINATOR_URL, customVersion);
        assertThat(testClient).isNotNull();
        testClient.close();
    }

    @Test
    @DisplayName("Should get all LRAs")
    void shouldGetAllLRAs() {
        // When
        Response response = client.getAllLRAs();

        // Then
        assertThat(response).isNotNull();
        verify(mockRequestBuilder).get();
    }

    @Test
    @DisplayName("Should get all LRAs with status filter")
    void shouldGetAllLRAsWithStatusFilter() {
        // When
        Response response = client.getAllLRAs(LRAStatus.Active, MediaType.APPLICATION_JSON);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).queryParam("Status", "Active");
        verify(mockRequestBuilder).get();
    }

    @Test
    @DisplayName("Should get LRA status")
    void shouldGetLRAStatus() {
        // When
        Response response = client.getLRAStatus(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).path("status");
        verify(mockRequestBuilder).get();
    }

    @Test
    @DisplayName("Should get LRA info")
    void shouldGetLRAInfo() {
        String lraDataJson = "{\"lraId\":\"" + TEST_LRA_URI + "\",\"clientId\":\"test-client\",\"status\":\"Active\"}";
        // When
        Response response = client.getLRAInfo(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
        verify(mockRequestBuilder).get();
    }

    @Test
    @DisplayName("Should start LRA with client ID")
    void shouldStartLRAWithClientId() {
        String clientId = "test-client-123";
        // When
        Response response = client.startLRA(clientId);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).path("start");
        verify(mockWebTarget).queryParam("ClientID", clientId);
        verify(mockRequestBuilder).post(any());
    }

    @Test
    @DisplayName("Should start LRA with timeout")
    void shouldStartLRAWithTimeout() {
        String clientId = "test-client-123";
        Long timeout = 60000L;
        // When
        Response response = client.startLRA(clientId, timeout);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).queryParam("ClientID", clientId);
        verify(mockWebTarget).queryParam("TimeLimit", timeout);
        verify(mockRequestBuilder).post(any());
    }

    @Test
    @DisplayName("Should start nested LRA")
    void shouldStartNestedLRA() {
        String clientId = "nested-client";
        Long timeout = 30000L;
        URI parentLRA = URI.create("http://localhost:8080/lra-coordinator/parent-123");
        // When
        Response response = client.startLRA(clientId, timeout, parentLRA, MediaType.APPLICATION_JSON);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).queryParam("ClientID", clientId);
        verify(mockWebTarget).queryParam("TimeLimit", timeout);
        verify(mockWebTarget).queryParam("ParentLRA", parentLRA.toString());
        verify(mockRequestBuilder).post(any());
    }

    @Test
    @DisplayName("Should renew LRA time limit")
    void shouldRenewLRATimeLimit() {
        Long newTimeout = 120000L;
        // When
        Response response = client.renewTimeLimit(TEST_LRA_URI, newTimeout);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).path("renew");
        verify(mockWebTarget).queryParam("TimeLimit", newTimeout);
        verify(mockRequestBuilder).put(any());
    }

    @Test
    @DisplayName("Should close LRA")
    void shouldCloseLRA() {
        // When
        Response response = client.closeLRA(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).path("close");
        verify(mockRequestBuilder).put(any());
    }

    @Test
    @DisplayName("Should close LRA with compensator and user data")
    void shouldCloseLRAWithCompensatorAndUserData() {
        String compensatorLink = "</compensate>; rel=\"compensate\"";
        String userData = "test-data";
        // When
        Response response = client.closeLRA(TEST_LRA_URI, MediaType.APPLICATION_JSON, compensatorLink, userData);

        // Then
        assertThat(response).isNotNull();
        verify(mockRequestBuilder).header("Narayana-LRA-Participant-Link", compensatorLink);
        verify(mockRequestBuilder).header("Narayana-LRA-Participant-Data", userData);
        verify(mockRequestBuilder).put(any());
    }

    @Test
    @DisplayName("Should cancel LRA")
    void shouldCancelLRA() {
        // When
        Response response = client.cancelLRA(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).path("cancel");
        verify(mockRequestBuilder).put(any());
    }

    @Test
    @DisplayName("Should join LRA")
    void shouldJoinLRA() {
        String compensatorLink = "</compensate>; rel=\"compensate\", </complete>; rel=\"complete\"";
        // When
        Response response = client.joinLRA(TEST_LRA_URI, compensatorLink);

        // Then
        assertThat(response).isNotNull();
        verify(mockRequestBuilder).header("Link", compensatorLink);
        verify(mockRequestBuilder).put(any());
    }

    @Test
    @DisplayName("Should leave LRA")
    void shouldLeaveLRA() {
        String participantUrl = "http://localhost:8080/participant/compensate";
        // When
        Response response = client.leaveLRA(TEST_LRA_URI, participantUrl);

        // Then
        assertThat(response).isNotNull();
        verify(mockWebTarget).path("remove");
        verify(mockRequestBuilder).put(any());
    }

    @Test
    @DisplayName("Should extract LRA ID from full URL")
    void shouldExtractLRAIdFromFullUrl() {
        URI fullLraUrl = URI.create("http://localhost:8080/lra-coordinator/test-lra-456");
        // When
        client.getLRAStatus(fullLraUrl);

        // Then
        verify(mockWebTarget).path("test-lra-456");
        verify(mockWebTarget).path("status");
    }

    @Test
    @DisplayName("Should handle LRA ID that's already just an ID")
    void shouldHandleLRAIdThatIsJustAnId() {
        URI simpleId = URI.create("simple-lra-id");
        // When
        client.getLRAStatus(simpleId);

        // Then
        verify(mockWebTarget).path("simple-lra-id");
        verify(mockWebTarget).path("status");
    }
}
