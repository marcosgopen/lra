# Security Configuration for NarayanaLRAClient

This document describes how to configure JWT authentication and SSL/TLS security for the NarayanaLRAClient.

## Overview

The NarayanaLRAClient supports comprehensive security configuration including:

1. **JWT Authentication**: MicroProfile JWT for API authentication
2. **SSL/TLS Configuration**: Custom truststore and keystore configuration
3. **Mutual TLS**: Client certificate authentication
4. **Hostname Verification**: Configurable certificate hostname validation

This is essential when your LRA coordinator requires secure, authenticated communication.

## Configuration

Security can be configured through MicroProfile Config properties or programmatically.

### SSL/TLS Configuration Properties

```properties
# SSL Truststore (for validating server certificates)
lra.coordinator.ssl.truststore.path=/path/to/truststore.jks
lra.coordinator.ssl.truststore.password=truststore-password
lra.coordinator.ssl.truststore.type=JKS

# SSL Keystore (for client certificates in mutual TLS)
lra.coordinator.ssl.keystore.path=/path/to/client-keystore.jks
lra.coordinator.ssl.keystore.password=keystore-password
lra.coordinator.ssl.keystore.type=JKS

# Hostname verification (default: true)
lra.coordinator.ssl.verify-hostname=true
```

### JWT Authentication Configuration

JWT authentication can be configured in several ways:

### 1. MicroProfile Config Properties

Add these properties to your `microprofile-config.properties` file or set them as system properties:

```properties
# Enable JWT authentication
lra.coordinator.jwt.enabled=true

# JWT token (when not using CDI injection)
lra.coordinator.jwt.token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...

# Optional: Custom header name (default: Authorization)
lra.coordinator.jwt.header=Authorization

# Optional: Custom token prefix (default: "Bearer ")
lra.coordinator.jwt.prefix=Bearer
```

### 2. Programmatic Configuration

```java
// Create the LRA client
NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

// Option A: Use configuration-based JWT (requires properties above)
ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
client.setAuthenticationFilter(jwtFilter);

// Option B: Use explicit JWT token
String jwtToken = obtainJwtTokenFromSecurityProvider();
ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter(jwtToken);
client.setAuthenticationFilter(jwtFilter);
```

### 3. CDI Injection (MicroProfile JWT)

When running in a CDI environment with MicroProfile JWT, the client can automatically use the current JWT context:

```java
@Inject
private NarayanaLRAClient client;

// In a method that runs within a JWT context
public void performLRAOperation() {
    // Configure to use injected JWT
    client.setAuthenticationFilter(JwtAuthenticationUtils.createJwtFilterIfEnabled());

    // The client will automatically use the current JWT token
    URI lra = client.startLRA("my-operation");
    // ... perform operations
}
```

## Authentication Modes

### 1. Injected JWT Mode
- **Use case**: Applications using MicroProfile JWT with CDI
- **Configuration**: Enable via `lra.coordinator.jwt.enabled=true`
- **Token source**: Automatically uses current `@Inject JsonWebToken`

### 2. Configured JWT Mode
- **Use case**: Standalone clients or explicit token management
- **Configuration**: Set `lra.coordinator.jwt.token` property
- **Token source**: Uses configured token value

### 3. Programmatic JWT Mode
- **Use case**: Dynamic token management
- **Configuration**: None required
- **Token source**: Explicitly provided token

## Security Considerations

1. **Token Security**: Ensure JWT tokens are obtained and stored securely
2. **Token Expiration**: Handle token refresh/renewal in your application
3. **Transport Security**: Use HTTPS for coordinator communication
4. **Token Validation**: Delegate token validation to the coordinator

## Examples

### Basic Configuration Example

```properties
# microprofile-config.properties
lra.coordinator.jwt.enabled=true
lra.coordinator.jwt.token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tIiwic3ViIjoibHJhLWNsaWVudCIsImF1ZCI6ImNvb3JkaW5hdG9yIiwiaWF0IjoxNjkwMDAwMDAwLCJleHAiOjE2OTAwMDM2MDB9.signature
```

```java
// Application code
NarayanaLRAClient client = new NarayanaLRAClient();
client.setAuthenticationFilter(JwtAuthenticationUtils.createJwtFilterIfEnabled());

// All coordinator requests will now include JWT authentication
URI lra = client.startLRA("secure-operation");
```

### Dynamic Token Example

```java
public class SecureLRAClient {
    private final NarayanaLRAClient client;
    private final TokenProvider tokenProvider;

    public SecureLRAClient(String coordinatorUrl, TokenProvider tokenProvider) {
        this.client = new NarayanaLRAClient(coordinatorUrl);
        this.tokenProvider = tokenProvider;
        refreshAuthentication();
    }

    public URI startSecureLRA(String operationId) {
        // Refresh token if needed
        if (tokenProvider.isTokenExpired()) {
            refreshAuthentication();
        }

        return client.startLRA(operationId);
    }

    private void refreshAuthentication() {
        String freshToken = tokenProvider.getValidToken();
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter(freshToken);
        client.setAuthenticationFilter(jwtFilter);
    }
}
```

### CDI Integration Example

```java
@ApplicationScoped
public class LRAService {

    @Inject
    private NarayanaLRAClient lraClient; // Assume CDI producer exists

    @PostConstruct
    public void init() {
        // Enable JWT authentication using CDI context
        lraClient.setAuthenticationFilter(JwtAuthenticationUtils.createJwtFilterIfEnabled());
    }

    @JWTRequired // MicroProfile JWT annotation
    public void performBusinessOperation() {
        // This method runs with JWT context, client will automatically use it
        URI lra = lraClient.startLRA("business-operation");
        // ... business logic
    }
}
```

## Troubleshooting

### Common Issues

1. **No Authentication Header**: Ensure JWT is enabled and token is configured
2. **Invalid Token**: Check token format and expiration
3. **CDI Injection Fails**: Verify MicroProfile JWT is properly configured
4. **Custom Headers**: Ensure coordinator expects the configured header/prefix

### Debug Configuration

Enable debug logging to troubleshoot authentication issues:

```properties
# Enable debug logging for LRA client
logging.level.io.narayana.lra.client=DEBUG
```

## Migration Guide

### Existing Applications

Existing applications will continue to work without changes. JWT authentication is disabled by default and must be explicitly enabled.

### Enabling JWT

1. Add MicroProfile JWT dependency (if not already present)
2. Configure JWT properties or implement programmatic setup
3. Test with your LRA coordinator

### Coordinator Requirements

Ensure your LRA coordinator supports JWT authentication and is configured to accept tokens from your JWT issuer.

## API Reference

### JwtAuthenticationFilter

Main filter class that handles JWT authentication logic.

**Configuration Properties:**
- `lra.coordinator.jwt.enabled`: Enable/disable JWT (default: false)
- `lra.coordinator.jwt.token`: JWT token value
- `lra.coordinator.jwt.header`: HTTP header name (default: "Authorization")
- `lra.coordinator.jwt.prefix`: Token prefix (default: "Bearer ")

### JwtAuthenticationUtils

Utility class for creating JWT filters.

**Methods:**
- `createJwtFilterIfEnabled()`: Creates filter if enabled in configuration
- `createJwtFilter(String token)`: Creates filter with explicit token

### NarayanaLRAClient

**Authentication Methods:**
- `setAuthenticationFilter(ClientRequestFilter filter)`: Set authentication filter (auto-reinitializes client)
- `getAuthenticationFilter()`: Get current authentication filter

**SSL/TLS Methods:**
- `setSslContext(SSLContext sslContext)`: Set custom SSL context (auto-reinitializes client)
- `getSslContext()`: Get current SSL context
- `setHostnameVerificationEnabled(boolean enabled)`: Enable/disable hostname verification (auto-reinitializes client)
- `isHostnameVerificationEnabled()`: Check if hostname verification is enabled

**Client Management Methods:**
- `reinitializeRestClient()`: Manually reinitialize the REST client with current settings

### SslUtils

Utility class for creating SSL contexts.

**Methods:**
- `createTrustAllSslContext()`: Create SSL context that trusts all certificates (development only)
- `createSslContextWithTruststore(...)`: Create SSL context with custom truststore
- `createMutualTlsSslContext(...)`: Create SSL context for mutual TLS authentication
- `createDefaultSslContext()`: Create SSL context using system defaults

## SSL/TLS Security

### Basic SSL Configuration

For HTTPS communication with LRA coordinators, configure SSL through properties:

```properties
# Truststore for validating coordinator certificates
lra.coordinator.ssl.truststore.path=/opt/security/truststore.jks
lra.coordinator.ssl.truststore.password=changeit
lra.coordinator.ssl.truststore.type=JKS

# Enable hostname verification (recommended for production)
lra.coordinator.ssl.verify-hostname=true
```

### Programmatic SSL Configuration

```java
// Custom truststore
SSLContext sslContext = SslUtils.createSslContextWithTruststore(
    "/path/to/truststore.jks", "password", "JKS"
);

NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");
client.setSslContext(sslContext);
```

### Mutual TLS (Client Certificates)

For environments requiring client certificate authentication:

```properties
# Truststore (validates server certificate)
lra.coordinator.ssl.truststore.path=/opt/security/truststore.jks
lra.coordinator.ssl.truststore.password=truststore-password

# Keystore (provides client certificate)
lra.coordinator.ssl.keystore.path=/opt/security/client-keystore.p12
lra.coordinator.ssl.keystore.password=keystore-password
lra.coordinator.ssl.keystore.type=PKCS12
```

```java
// Programmatic mutual TLS setup
SSLContext mutualTlsContext = SslUtils.createMutualTlsSslContext(
    "/path/to/truststore.jks", "trust-password", "JKS",
    "/path/to/client-keystore.p12", "key-password", "PKCS12"
);

client.setSslContext(mutualTlsContext);
```

### Development/Testing SSL

For development environments with self-signed certificates:

```java
// WARNING: Only for development - trusts all certificates
SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
client.setSslContext(trustAllContext);
client.setHostnameVerificationEnabled(false);
```

### SSL Best Practices

1. **Production**: Always use proper certificates with hostname verification
2. **Truststore**: Include only trusted CA certificates
3. **Client Certificates**: Secure storage and regular rotation
4. **Protocols**: Use TLS 1.2 or higher
5. **Development**: Never disable SSL verification in production

## Complete Security Configuration Example

```properties
# Coordinator URL with HTTPS
lra.coordinator.url=https://secure-coordinator.example.com/lra-coordinator

# SSL Configuration
lra.coordinator.ssl.truststore.path=/opt/security/truststore.jks
lra.coordinator.ssl.truststore.password=truststore-password
lra.coordinator.ssl.truststore.type=JKS
lra.coordinator.ssl.verify-hostname=true

# JWT Authentication
lra.coordinator.jwt.enabled=true
lra.coordinator.jwt.token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

```java
// Application code with full security
NarayanaLRAClient client = new NarayanaLRAClient();

// SSL is configured automatically from properties
// JWT is configured from properties
ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
client.setAuthenticationFilter(jwtFilter);

// Client now has both SSL and JWT security enabled
URI lra = client.startLRA("secure-operation");
```

## Dynamic Configuration Updates

The NarayanaLRAClient supports dynamic configuration updates without requiring client recreation. When security settings are updated, the REST client is automatically reinitialized with the new configuration.

### Runtime Authentication Updates

```java
NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

// Start with no authentication
URI lra1 = client.startLRA("operation-1");

// Add JWT authentication - client automatically reinitializes
String token = obtainJwtTokenFromProvider();
client.setAuthenticationFilter(JwtAuthenticationUtils.createJwtFilter(token));

// All subsequent requests now include authentication
URI lra2 = client.startLRA("operation-2");

// Update with refreshed token
String refreshedToken = refreshJwtToken();
client.setAuthenticationFilter(JwtAuthenticationUtils.createJwtFilter(refreshedToken));

URI lra3 = client.startLRA("operation-3");
```

### Runtime SSL Updates

```java
NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

// Environment-based SSL configuration
String environment = System.getProperty("environment");

if ("production".equals(environment)) {
    SSLContext prodContext = SslUtils.createSslContextWithTruststore(
        "/opt/security/prod-truststore.jks", "prod-password", "JKS");
    client.setSslContext(prodContext);
    client.setHostnameVerificationEnabled(true);
} else {
    // Development: trust all certificates (INSECURE)
    SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
    client.setSslContext(trustAllContext);
    client.setHostnameVerificationEnabled(false);
}
```

### Manual Reinitialization

For cases where you want to control when the client reinitializes:

```java
NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

// Set multiple configurations (each would normally trigger reinitialization)
// Note: Current implementation reinitializes on each setter call

// Alternative: Manual reinitialization after setting multiple properties
client.reinitializeRestClient(); // Force reinitialization with current settings
```

### Configuration Hot-Reload

The client supports configuration updates from external sources:

```java
// Configuration update from external source (config server, etc.)
public void updateClientConfiguration(NarayanaLRAClient client, SecurityConfig newConfig) {
    if (newConfig.hasSslChanges()) {
        client.setSslContext(newConfig.createSslContext());
    }

    if (newConfig.hasAuthChanges()) {
        client.setAuthenticationFilter(newConfig.createAuthFilter());
    }

    // Client is automatically updated with new settings
}
```

## Performance Considerations

1. **Automatic Reinitialization**: Each setter call triggers REST client reinitialization
2. **Batch Updates**: Consider the order of configuration updates to minimize reinitializations
3. **Production Usage**: Configuration changes should be infrequent in production environments
4. **Resource Cleanup**: The old REST client is automatically cleaned up during reinitialization