# LRA Client Refactoring Migration Guide

This guide helps you migrate from the original NarayanaLRAClient implementation to the new refactored architecture.

## Overview of Changes

The LRA client has been refactored to improve:
- **Separation of Concerns**: Configuration, client creation, and business logic are now separated
- **Testability**: Dependencies are now injectable and easier to mock
- **Maintainability**: Code is organized into focused, single-purpose classes
- **Configuration Management**: Centralized configuration using MicroProfile Config
- **Exception Handling**: Consistent exception hierarchy with proper error codes
- **Builder Pattern**: Fluent API for client creation and configuration

## Architecture Changes

### Before (Original)
```java
// Everything was in one large class
NarayanaLRAClient client = new NarayanaLRAClient(coordinatorUrl);
client.setAuthenticationFilter(jwtFilter);
client.setSslContext(sslContext);
```

### After (Refactored)
```java
// Using builder pattern with clear separation
NarayanaLRAClient client = LRAClientBuilder.newBuilder()
    .coordinatorUrl(coordinatorUrl)
    .withAuthentication(jwtFilter)
    .withSslContext(sslContext)
    .build();
```

## Migration Steps

### 1. Update Client Creation

#### Before:
```java
NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator/lra");
```

#### After:
```java
NarayanaLRAClient client = LRAClientBuilder.newBuilder()
    .coordinatorUrl("https://coordinator/lra")
    .build();
```

### 2. Configuration-Based Setup

#### Before:
```java
// Manual property reading
String url = System.getProperty("lra.coordinator.url", "http://localhost:8080/lra-coordinator");
NarayanaLRAClient client = new NarayanaLRAClient(url);

// Manual SSL configuration
if (System.getProperty("lra.coordinator.ssl.truststore.path") != null) {
    SSLContext sslContext = createSslContextFromConfig();
    client.setSslContext(sslContext);
}
```

#### After:
```java
// Automatic configuration via MicroProfile Config
LRAClientConfig config = ConfigProvider.getConfig().getConfigMapping(LRAClientConfig.class);
NarayanaLRAClient client = LRAClientBuilder.newBuilder(config).build();
```

### 3. Authentication Configuration

#### Before:
```java
NarayanaLRAClient client = new NarayanaLRAClient(url);
if (jwtEnabled) {
    ClientRequestFilter jwtFilter = new JwtAuthenticationFilter();
    client.setAuthenticationFilter(jwtFilter);
}
```

#### After:
```java
NarayanaLRAClient client = LRAClientBuilder.newBuilder()
    .coordinatorUrl(url)
    .withAuthentication(JwtAuthenticationUtils.createJwtFilterIfEnabled())
    .build();
```

### 4. SSL Configuration

#### Before:
```java
NarayanaLRAClient client = new NarayanaLRAClient(url);
SSLContext sslContext = SslUtils.createSslContextWithTruststore(
    truststorePath, truststorePassword, truststoreType);
client.setSslContext(sslContext);
client.setHostnameVerificationEnabled(false);
```

#### After:
```java
NarayanaLRAClient client = LRAClientBuilder.newBuilder()
    .coordinatorUrl(url)
    .ssl()
        .context(SslUtils.createSslContextWithTruststore(
            truststorePath, truststorePassword, truststoreType))
        .hostnameVerification(false)
    .build();
```

### 5. Exception Handling

#### Before:
```java
try {
    URI lra = client.startLRA("my-lra");
} catch (WebApplicationException e) {
    // Generic exception handling
    logger.error("LRA operation failed", e);
}
```

#### After:
```java
try {
    URI lra = client.startLRA("my-lra");
} catch (LRALifecycleException e) {
    // Specific LRA lifecycle error
    logger.error("LRA {} operation failed: {}", e.getOperation(), e.getMessage());
} catch (LRACoordinatorUnavailableException e) {
    // Coordinator unavailable
    logger.error("Coordinator unavailable: {}", e.getMessage());
} catch (LRAConfigurationException e) {
    // Configuration error
    logger.error("Configuration error {}: {}", e.getErrorCode(), e.getMessage());
}
```

## Configuration Properties

The refactored client uses the same configuration properties but with better structure:

### SSL Configuration
```properties
lra.coordinator.ssl.truststore.path=/path/to/truststore.jks
lra.coordinator.ssl.truststore.password=password
lra.coordinator.ssl.truststore.type=JKS
lra.coordinator.ssl.keystore.path=/path/to/keystore.jks
lra.coordinator.ssl.keystore.password=password
lra.coordinator.ssl.keystore.type=JKS
lra.coordinator.ssl.verify-hostname=true
```

### JWT Configuration
```properties
lra.coordinator.jwt.enabled=true
lra.coordinator.jwt.token=your-jwt-token
lra.coordinator.jwt.header=Authorization
lra.coordinator.jwt.prefix=Bearer
```

### Load Balancer Configuration
```properties
lra.coordinator.url=https://coord1.example.com/lra,https://coord2.example.com/lra
lra.coordinator.lb-method=round-robin
```

## Breaking Changes

### 1. Package Structure
- New packages: `io.narayana.lra.client.config`, `io.narayana.lra.client.builder`, `io.narayana.lra.client.exception`
- Some internal classes moved to more appropriate packages

### 2. Exception Types
- `WebApplicationException` is still thrown but now specific subclasses are used
- New exception hierarchy provides better error categorization

### 3. Constants
- All constants moved to `LRAClientConstants` class
- Remove direct usage of string literals in favor of constants

## Benefits of Migration

### 1. Better Testability
```java
@Test
public void testLRAClient() {
    // Mock configuration
    LRAClientConfig mockConfig = createMockConfig();

    // Create client with mocked dependencies
    NarayanaLRAClient client = LRAClientBuilder.newBuilder(mockConfig).build();

    // Test client behavior
    assertThat(client.getCoordinatorUrl()).isEqualTo("mock://coordinator");
}
```

### 2. Cleaner Configuration
```java
// Configuration is centralized and type-safe
@ConfigMapping(prefix = "lra.coordinator")
public interface MyLRAConfig extends LRAClientConfig {
    // Additional application-specific configuration
    Optional<String> applicationId();
}
```

### 3. Better Error Handling
```java
// Specific exception handling with error codes
catch (LRAConfigurationException e) {
    if ("SSL_CONFIG_ERROR".equals(e.getErrorCode())) {
        // Handle SSL configuration errors specifically
        handleSslConfigError(e);
    }
}
```

## Compatibility

The refactored architecture maintains backward compatibility for:
- All public API methods of `NarayanaLRAClient`
- Configuration property names and formats
- LRA protocol behavior

## Example Migration

Here's a complete example showing before and after:

### Before:
```java
public class OldLRAService {
    private NarayanaLRAClient client;

    @PostConstruct
    public void init() {
        String url = ConfigProvider.getConfig()
            .getValue("lra.coordinator.url", String.class);
        client = new NarayanaLRAClient(url);

        // Configure SSL manually
        String truststore = getConfig("lra.coordinator.ssl.truststore.path", null);
        if (truststore != null) {
            try {
                SSLContext ssl = createSslContextFromConfig();
                client.setSslContext(ssl);
            } catch (Exception e) {
                throw new RuntimeException("SSL configuration failed", e);
            }
        }

        // Configure JWT manually
        boolean jwtEnabled = getConfigBoolean("lra.coordinator.jwt.enabled", false);
        if (jwtEnabled) {
            String token = getConfig("lra.coordinator.jwt.token", null);
            if (token != null) {
                client.setAuthenticationFilter(new JwtAuthenticationFilter());
            }
        }
    }
}
```

### After:
```java
public class NewLRAService {
    @Inject
    private LRAClientConfig config;

    private NarayanaLRAClient client;

    @PostConstruct
    public void init() {
        client = LRAClientBuilder.newBuilder(config).build();
    }
}
```

The new approach is significantly cleaner and more maintainable!