/*
  Copyright The Narayana Authors
  SPDX-License-Identifier: Apache-2.0
*/

package io.narayana.lra.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LRA Coordinator Config Tests")
class LRACoordinatorConfigTest {

    @Mock
    private Config mockConfig;

    private static final String SINGLE_URL = "http://localhost:8080/lra-coordinator/";
    private static final String CLUSTER_URLS = "http://host1:8080/lra-coordinator/,http://host2:8080/lra-coordinator/";

    @BeforeEach
    void setUp() {
        // Set up default values
        when(mockConfig.getOptionalValue("lra.coordinator.service-name", String.class))
                .thenReturn(Optional.of("lra-coordinator"));
        when(mockConfig.getOptionalValue("lra.coordinator.lb-method", String.class))
                .thenReturn(Optional.of("round-robin"));
        when(mockConfig.getOptionalValue("lra.coordinator.timeout", Long.class))
                .thenReturn(Optional.of(30L));
        when(mockConfig.getOptionalValue("lra.coordinator.max-retries", Integer.class))
                .thenReturn(Optional.of(3));
        when(mockConfig.getOptionalValue("lra.coordinator.api-version", String.class))
                .thenReturn(Optional.of("1.0"));
    }

    @Test
    @DisplayName("Should load single coordinator configuration")
    void shouldLoadSingleCoordinatorConfiguration() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        // When
        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // Then
        assertThat(config.isClustered()).isFalse();
        assertThat(config.getSingleUrl()).isEqualTo(SINGLE_URL);
        assertThat(config.getClusterUrls()).isEmpty();
        assertThat(config.getServiceName()).isEqualTo("lra-coordinator");
        assertThat(config.getLoadBalancingMethod()).isEqualTo("round-robin");
        assertThat(config.getOperationTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getMaxRetries()).isEqualTo(3);
        assertThat(config.getApiVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("Should load cluster coordinator configuration")
    void shouldLoadClusterCoordinatorConfiguration() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.of(CLUSTER_URLS));

        // When
        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // Then
        assertThat(config.isClustered()).isTrue();
        assertThat(config.getSingleUrl()).isNull();
        assertThat(config.getClusterUrls()).containsExactly(
                "http://host1:8080/lra-coordinator/",
                "http://host2:8080/lra-coordinator/");
    }

    @Test
    @DisplayName("Should load custom configuration values")
    void shouldLoadCustomConfigurationValues() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.service-name", String.class))
                .thenReturn(Optional.of("custom-service"));
        when(mockConfig.getOptionalValue("lra.coordinator.lb-method", String.class))
                .thenReturn(Optional.of("random"));
        when(mockConfig.getOptionalValue("lra.coordinator.timeout", Long.class))
                .thenReturn(Optional.of(60L));
        when(mockConfig.getOptionalValue("lra.coordinator.max-retries", Integer.class))
                .thenReturn(Optional.of(5));
        when(mockConfig.getOptionalValue("lra.coordinator.api-version", String.class))
                .thenReturn(Optional.of("2.0"));

        // When
        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // Then
        assertThat(config.getServiceName()).isEqualTo("custom-service");
        assertThat(config.getLoadBalancingMethod()).isEqualTo("random");
        assertThat(config.getOperationTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getMaxRetries()).isEqualTo(5);
        assertThat(config.getApiVersion()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("Should create single coordinator client")
    void shouldCreateSingleCoordinatorClient() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When
        AutoCloseable client = config.createClient();

        // Then
        assertThat(client).isInstanceOf(LRACoordinatorClient.class);
        client = null; // Don't close as it would try to close a real HTTP client
    }

    @Test
    @DisplayName("Should create clustered coordinator client")
    void shouldCreateClusteredCoordinatorClient() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.of(CLUSTER_URLS));

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When
        AutoCloseable client = config.createClient();

        // Then
        assertThat(client).isInstanceOf(ClusteredLRACoordinatorClient.class);

        // Clean up
        try {
            client.close();
        } catch (Exception e) {
            // Ignore cleanup errors in tests
        }
    }

    @Test
    @DisplayName("Should throw exception when no configuration found")
    void shouldThrowExceptionWhenNoConfigurationFound() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When & Then
        assertThatThrownBy(config::createClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No LRA coordinator configuration found");
    }

    @Test
    @DisplayName("Should validate configuration successfully")
    void shouldValidateConfigurationSuccessfully() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When & Then
        config.validate(); // Should not throw
    }

    @Test
    @DisplayName("Should fail validation with invalid max retries")
    void shouldFailValidationWithInvalidMaxRetries() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.max-retries", Integer.class))
                .thenReturn(Optional.of(0));

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When & Then
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Max retries must be at least 1");
    }

    @Test
    @DisplayName("Should fail validation with invalid timeout")
    void shouldFailValidationWithInvalidTimeout() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.timeout", Long.class))
                .thenReturn(Optional.of(0L));

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When & Then
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Operation timeout must be positive");
    }

    @Test
    @DisplayName("Should fail validation with empty cluster URLs")
    void shouldFailValidationWithEmptyClusterUrls() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.empty());
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.of("")); // Empty string

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When & Then
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cluster mode enabled but no coordinator URLs provided");
    }

    @Test
    @DisplayName("Should get configuration value with optional")
    void shouldGetConfigurationValueWithOptional() {
        // Given
        String customKey = "custom.property";
        String customValue = "custom-value";
        when(mockConfig.getOptionalValue(customKey, String.class))
                .thenReturn(Optional.of(customValue));

        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When
        Optional<String> value = config.getValue(customKey, String.class);

        // Then
        assertThat(value).contains(customValue);
    }

    @Test
    @DisplayName("Should get configuration value with default")
    void shouldGetConfigurationValueWithDefault() {
        // Given
        String customKey = "missing.property";
        String defaultValue = "default-value";
        when(mockConfig.getOptionalValue(eq(customKey), any(Class.class)))
                .thenReturn(Optional.empty());

        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When
        String value = config.getValue(customKey, defaultValue);

        // Then
        assertThat(value).isEqualTo(defaultValue);
    }

    @Test
    @DisplayName("Should generate toString with all configuration values")
    void shouldGenerateToStringWithAllConfigurationValues() {
        // Given
        when(mockConfig.getOptionalValue("lra.coordinator.url", String.class))
                .thenReturn(Optional.of(SINGLE_URL));
        when(mockConfig.getOptionalValue("lra.coordinator.urls", String.class))
                .thenReturn(Optional.empty());

        LRACoordinatorConfig config = new LRACoordinatorConfig(mockConfig);

        // When
        String toString = config.toString();

        // Then
        assertThat(toString).contains("LRACoordinatorConfig");
        assertThat(toString).contains("clustered=false");
        assertThat(toString).contains("singleUrl='" + SINGLE_URL + "'");
        assertThat(toString).contains("serviceName='lra-coordinator'");
        assertThat(toString).contains("loadBalancingMethod='round-robin'");
        assertThat(toString).contains("operationTimeout=PT30S");
        assertThat(toString).contains("maxRetries=3");
        assertThat(toString).contains("apiVersion='1.0'");
    }
}
