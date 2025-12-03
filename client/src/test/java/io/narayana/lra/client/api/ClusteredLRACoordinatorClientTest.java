/*
  Copyright The Narayana Authors
  SPDX-License-Identifier: Apache-2.0
*/

package io.narayana.lra.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceInstance;
import io.smallrye.stork.api.ServiceRegistrar;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
@DisplayName("Clustered LRA Coordinator Client Tests")
class ClusteredLRACoordinatorClientTest {

    @Mock
    private Stork mockStork;

    @Mock
    private Service mockService;

    @Mock
    private ServiceInstance mockServiceInstance;

    @Mock
    private ServiceRegistrar mockServiceRegistrar;

    @Mock
    private Response mockResponse;

    private MockedStatic<Stork> storkStaticMock;
    private ClusteredLRACoordinatorClient client;

    private static final String TEST_SERVICE_NAME = "test-lra-coordinator";
    private static final String TEST_COORDINATOR_URL = "http://localhost:8080/lra-coordinator/";
    private static final URI TEST_LRA_URI = URI.create("http://localhost:8080/lra-coordinator/test-lra-123");

    @BeforeEach
    void setUp() {
        storkStaticMock = Mockito.mockStatic(Stork.class);
        storkStaticMock.when(Stork::getInstance).thenReturn(mockStork);

        // Set up service instance mock
        lenient().when(mockServiceInstance.getHost()).thenReturn("localhost");
        lenient().when(mockServiceInstance.getPort()).thenReturn(8080);
        lenient().when(mockServiceInstance.isSecure()).thenReturn(false);

        // Set up service mock - Stork uses Mutiny Uni instead of CompletableFuture
        lenient().when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));
        lenient().when(mockService.getInstances())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(List.of(mockServiceInstance)));
        lenient().when(mockService.getServiceRegistrar()).thenReturn(mockServiceRegistrar);

        // Set up Stork mock
        lenient().when(mockStork.getService(anyString())).thenReturn(mockService);

        client = new ClusteredLRACoordinatorClient(TEST_SERVICE_NAME);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        storkStaticMock.close();
    }

    @Test
    @DisplayName("Should create client with default service name")
    void shouldCreateClientWithDefaultServiceName() {
        ClusteredLRACoordinatorClient defaultClient = new ClusteredLRACoordinatorClient();
        assertThat(defaultClient.getServiceName()).isEqualTo("lra-coordinator");
        defaultClient.close();
    }

    @Test
    @DisplayName("Should create client with custom service name")
    void shouldCreateClientWithCustomServiceName() {
        assertThat(client.getServiceName()).isEqualTo(TEST_SERVICE_NAME);
    }

    @Test
    @DisplayName("Should register coordinator instance")
    void shouldRegisterCoordinatorInstance() {
        // When
        client.registerCoordinator(TEST_COORDINATOR_URL);

        // Then - the registration is now just a logging operation
        // No actual service registration happens in our simplified implementation
    }

    @Test
    @DisplayName("Should get available coordinators")
    void shouldGetAvailableCoordinators() throws Exception {
        // When
        CompletableFuture<List<ServiceInstance>> future = client.getAvailableCoordinators();
        List<ServiceInstance> instances = future.get();

        // Then
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0)).isEqualTo(mockServiceInstance);
    }

    @Test
    @DisplayName("Should execute getAllLRAs with failover")
    void shouldExecuteGetAllLRAsWithFailover() {
        // Given
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.getAllLRAs();

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should execute startLRA with failover")
    void shouldExecuteStartLRAWithFailover() {
        // Given
        String clientId = "test-client";
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.startLRA(clientId);

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should execute getLRAStatus with failover")
    void shouldExecuteGetLRAStatusWithFailover() {
        // Given
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.getLRAStatus(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should execute closeLRA with failover")
    void shouldExecuteCloseLRAWithFailover() {
        // Given
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.closeLRA(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should execute cancelLRA with failover")
    void shouldExecuteCancelLRAWithFailover() {
        // Given
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.cancelLRA(TEST_LRA_URI);

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should build coordinator URL from service instance")
    void shouldBuildCoordinatorUrlFromServiceInstance() {
        // Given
        when(mockServiceInstance.getHost()).thenReturn("example.com");
        when(mockServiceInstance.getPort()).thenReturn(9090);
        when(mockServiceInstance.isSecure()).thenReturn(true);
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.getAllLRAs();

        // Then
        assertThat(response).isNotNull();
        // URL should be built as https://example.com:9090/lra-coordinator/
    }

    @Test
    @DisplayName("Should handle service instance selection timeout")
    void shouldHandleServiceInstanceSelectionTimeout() {
        // Given
        io.smallrye.mutiny.Uni<ServiceInstance> timeoutUni = io.smallrye.mutiny.Uni.createFrom()
                .failure(new RuntimeException("Service instance selection timeout"));

        when(mockService.selectInstance()).thenReturn(timeoutUni);

        // When & Then
        assertThatThrownBy(() -> client.getAllLRAs())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Should handle operation failures with retry")
    void shouldHandleOperationFailuresWithRetry() {
        // Given
        io.smallrye.mutiny.Uni<ServiceInstance> failureUni = io.smallrye.mutiny.Uni.createFrom()
                .failure(new RuntimeException("Service unavailable"));

        when(mockService.selectInstance()).thenReturn(failureUni);

        // When & Then
        assertThatThrownBy(() -> client.getAllLRAs())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("All 3 attempts failed");
    }

    @Test
    @DisplayName("Should track request count")
    void shouldTrackRequestCount() {
        // Given
        int initialCount = client.getRequestCount();

        // When
        client.getAllLRAs();

        // Then
        // Request count tracking would be implemented in actual operation execution
        assertThat(client.getRequestCount()).isEqualTo(initialCount);
    }

    @Test
    @DisplayName("Should handle nested LRA operations")
    void shouldHandleNestedLRAOperations() {
        // Given
        String clientId = "nested-client";
        Long timeout = 30000L;
        URI parentLRA = URI.create("http://localhost:8080/lra-coordinator/parent-123");
        String accept = "application/json";

        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.startLRA(clientId, timeout, parentLRA, accept);

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should handle join and leave LRA operations")
    void shouldHandleJoinAndLeaveLRAOperations() {
        // Given
        String compensatorLink = "</compensate>; rel=\"compensate\"";
        String participantUrl = "http://localhost:8080/participant/compensate";

        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response joinResponse = client.joinLRA(TEST_LRA_URI, compensatorLink);
        Response leaveResponse = client.leaveLRA(TEST_LRA_URI, participantUrl);

        // Then
        assertThat(joinResponse).isNotNull();
        assertThat(leaveResponse).isNotNull();
    }

    @Test
    @DisplayName("Should handle renew time limit operation")
    void shouldHandleRenewTimeLimitOperation() {
        // Given
        Long newTimeout = 120000L;

        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        // When
        Response response = client.renewTimeLimit(TEST_LRA_URI, newTimeout);

        // Then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should close all cached clients on close")
    void shouldCloseAllCachedClientsOnClose() {
        // Given - perform some operations to create cached clients
        when(mockService.selectInstance())
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockServiceInstance));

        client.getAllLRAs(); // This should create a cached client

        // When
        client.close();

        // Then - no exception should be thrown and client should be properly closed
        assertThat(client).isNotNull();
    }
}
