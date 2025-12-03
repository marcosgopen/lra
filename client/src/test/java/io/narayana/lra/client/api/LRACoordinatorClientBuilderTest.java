/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LRA Coordinator Client Builder Tests")
class LRACoordinatorClientBuilderTest {

    private static final String TEST_COORDINATOR_URL = "http://localhost:8080";

    @Test
    @DisplayName("Should create builder instance")
    void shouldCreateBuilderInstance() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder();
        assertThat(builder).isNotNull();
    }

    @Test
    @DisplayName("Should build client with coordinator URL")
    void shouldBuildClientWithCoordinatorUrl() {
        try (AutoCloseable client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .build()) {

            assertThat(client).isNotNull();
            assertThat(client).isInstanceOf(LRACoordinatorClient.class);
        } catch (Exception e) {
            // Should not happen
        }
    }

    @Test
    @DisplayName("Should build client with custom API version")
    void shouldBuildClientWithCustomApiVersion() {
        String customVersion = "2.0";

        try (AutoCloseable client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .apiVersion(customVersion)
                .build()) {

            assertThat(client).isNotNull();
            assertThat(client).isInstanceOf(LRACoordinatorClient.class);
        } catch (Exception e) {
            // Should not happen
        }
    }

    @Test
    @DisplayName("Should build client with custom timeouts")
    void shouldBuildClientWithCustomTimeouts() {
        try (AutoCloseable client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()) {

            assertThat(client).isNotNull();
            assertThat(client).isInstanceOf(LRACoordinatorClient.class);
        } catch (Exception e) {
            // Should not happen
        }
    }

    @Test
    @DisplayName("Should build clustered client with multiple URLs")
    void shouldBuildClusteredClientWithMultipleUrls() {
        String[] urls = {
                "http://host1:8080/lra-coordinator/",
                "http://host2:8080/lra-coordinator/"
        };

        try (ClusteredLRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrls(urls)
                .buildClusteredClient()) {

            assertThat(client).isNotNull();
            assertThat(client.getServiceName()).isEqualTo("lra-coordinator");
        }
    }

    @Test
    @DisplayName("Should build clustered client with custom service name")
    void shouldBuildClusteredClientWithCustomServiceName() {
        String serviceName = "custom-lra-coordinator";

        try (ClusteredLRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrls("http://host1:8080/lra-coordinator/")
                .serviceName(serviceName)
                .buildClusteredClient()) {

            assertThat(client).isNotNull();
            assertThat(client.getServiceName()).isEqualTo(serviceName);
        }
    }

    @Test
    @DisplayName("Should build clustered client with custom load balancing method")
    void shouldBuildClusteredClientWithCustomLoadBalancingMethod() {
        try (ClusteredLRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrls("http://host1:8080/lra-coordinator/")
                .loadBalancingMethod("random")
                .buildClusteredClient()) {

            assertThat(client).isNotNull();
        }
    }

    @Test
    @DisplayName("Should enable clustered mode with default configuration")
    void shouldEnableClusteredModeWithDefaultConfiguration() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder()
                .clustered();

        assertThat(builder.isClustered()).isTrue();
        assertThat(builder.getServiceName()).isEqualTo("lra-coordinator");
        assertThat(builder.getLoadBalancingMethod()).isEqualTo("round-robin");
    }

    @Test
    @DisplayName("Should enable clustered mode with custom configuration")
    void shouldEnableClusteredModeWithCustomConfiguration() {
        String serviceName = "custom-service";
        String loadBalancingMethod = "sticky";

        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder()
                .clustered(serviceName, loadBalancingMethod);

        assertThat(builder.isClustered()).isTrue();
        assertThat(builder.getServiceName()).isEqualTo(serviceName);
        assertThat(builder.getLoadBalancingMethod()).isEqualTo(loadBalancingMethod);
    }

    @Test
    @DisplayName("Should automatically enable clustered mode when URLs are added")
    void shouldAutomaticallyEnableClusteredModeWhenUrlsAreAdded() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrls("http://host1:8080/", "http://host2:8080/");

        assertThat(builder.isClustered()).isTrue();
        assertThat(builder.getCoordinatorUrls()).hasSize(2);
    }

    @Test
    @DisplayName("Should disable clustered mode when single URL is set")
    void shouldDisableClusteredModeWhenSingleUrlIsSet() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrls("http://host1:8080/")
                .coordinatorUrl(TEST_COORDINATOR_URL);

        assertThat(builder.isClustered()).isFalse();
    }

    @Test
    @DisplayName("Should build appropriate client type based on configuration")
    void shouldBuildAppropriateClientTypeBasedOnConfiguration() {
        // Single client
        try (AutoCloseable singleClient = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .build()) {
            assertThat(singleClient).isInstanceOf(LRACoordinatorClient.class);
        } catch (Exception e) {
            // Should not happen
        }

        // Clustered client
        try (AutoCloseable clusteredClient = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrls("http://host1:8080/")
                .build()) {
            assertThat(clusteredClient).isInstanceOf(ClusteredLRACoordinatorClient.class);
        } catch (Exception e) {
            // Should not happen
        }
    }

    @Test
    @DisplayName("Should throw exception when coordinator URL is null")
    void shouldThrowExceptionWhenCoordinatorUrlIsNull() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(null)
                .buildSingleClient()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified for single client mode");
    }

    @Test
    @DisplayName("Should throw exception when coordinator URL is empty")
    void shouldThrowExceptionWhenCoordinatorUrlIsEmpty() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl("")
                .buildSingleClient()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified for single client mode");
    }

    @Test
    @DisplayName("Should throw exception when coordinator URL is blank")
    void shouldThrowExceptionWhenCoordinatorUrlIsBlank() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl("   ")
                .buildSingleClient()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified for single client mode");
    }

    @Test
    @DisplayName("Should throw exception when no coordinator URL is set for single client")
    void shouldThrowExceptionWhenNoCoordinatorUrlIsSetForSingleClient() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .apiVersion("1.0")
                .buildSingleClient()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified for single client mode");
    }

    @Test
    @DisplayName("Should throw exception when service name is null for clustered client")
    void shouldThrowExceptionWhenServiceNameIsNullForClusteredClient() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .serviceName(null)
                .buildClusteredClient()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Service name must be specified for clustered client mode");
    }

    @Test
    @DisplayName("Should throw exception when service name is empty for clustered client")
    void shouldThrowExceptionWhenServiceNameIsEmptyForClusteredClient() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .serviceName("")
                .buildClusteredClient()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Service name must be specified for clustered client mode");
    }

    @Test
    @DisplayName("Should allow method chaining")
    void shouldAllowMethodChaining() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder();

        LRACoordinatorClientBuilder result = builder
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .apiVersion("1.0")
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS);

        assertThat(result).isSameAs(builder);

        try (AutoCloseable client = result.build()) {
            assertThat(client).isNotNull();
            assertThat(client).isInstanceOf(LRACoordinatorClient.class);
        } catch (Exception e) {
            // Should not happen
        }
    }
}