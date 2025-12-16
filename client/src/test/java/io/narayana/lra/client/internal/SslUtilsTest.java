/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.client.internal;

import static org.junit.jupiter.api.Assertions.*;

import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * Tests for SSL utility methods to verify they work correctly.
 */
public class SslUtilsTest {

    @Test
    public void testCreateTrustAllSslContext() throws Exception {
        // When
        SSLContext sslContext = SslUtils.createTrustAllSslContext();
        // Then
        assertNotNull(sslContext, "Trust-all SSL context should be created");
        assertEquals("TLS", sslContext.getProtocol(), "Should use TLS protocol");
        // At minimum, verify the context is usable
        assertNotNull(sslContext.getSocketFactory(), "SSL context should have a socket factory");
    }

    @Test
    public void testCreateDefaultSslContext() throws Exception {
        // When
        SSLContext defaultContext = SslUtils.createDefaultSslContext();

        // Then
        assertNotNull(defaultContext, "Default SSL context should be created");
        assertNotNull(defaultContext.getSocketFactory(), "Should have socket factory");
        assertNotNull(defaultContext.getServerSocketFactory(), "Should have server socket factory");
    }

    @Test
    public void testCreateSslContextWithTruststoreFileNotFound() {
        // When attempting to create SSL context with non-existent truststore
        try {
            SSLContext sslContext = SslUtils.createSslContextWithTruststore("/nonexistent/path/truststore.jks",
                    "password", "JKS");
            fail("Should throw exception for non-existent truststore file");
        } catch (Exception e) {
            // Then
            assertTrue(
                    e instanceof java.io.FileNotFoundException || e.getCause() instanceof java.io.FileNotFoundException
                            || e.getMessage().contains("FileNotFoundException")
                            || e.getMessage().contains("No such file"),
                    "Should be file-related exception");
        }
    }

    @Test
    public void testCreateSslContextWithTruststoreInvalidType() {
        // When attempting to create SSL context with invalid keystore type
        try {
            SSLContext sslContext = SslUtils.createSslContextWithTruststore("/nonexistent/path/truststore.jks",
                    "password", "INVALID_TYPE");
            fail("Should throw exception for invalid keystore type");
        } catch (Exception e) {
            // Then
            assertTrue(e.getMessage().contains("INVALID_TYPE") || e instanceof java.security.KeyStoreException,
                    "Should be keystore type related exception");
        }
    }

    @Test
    public void testCreateMutualTlsSslContextFileNotFound() {
        // When attempting to create mutual TLS SSL context with non-existent files
        try {
            SSLContext sslContext = SslUtils.createMutualTlsSslContext("/nonexistent/truststore.jks", "trust-pass",
                    "JKS", "/nonexistent/keystore.jks", "key-pass", "JKS");
            fail("Should throw exception for non-existent keystore files");
        } catch (Exception e) {
            // Then
            assertTrue(
                    e instanceof java.io.FileNotFoundException || e.getCause() instanceof java.io.FileNotFoundException
                            || e.getMessage().contains("FileNotFoundException")
                            || e.getMessage().contains("No such file"),
                    "Should be file-related exception");
        }
    }

    @Test
    public void testSslContextTypes() throws Exception {
        // Test that we can create different types of SSL contexts
        // Default context
        SSLContext defaultContext = SslUtils.createDefaultSslContext();
        assertNotNull(defaultContext, "Default context should be created");
        // Trust-all context
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
        assertNotNull(trustAllContext, "Trust-all context should be created");
        // Verify they are different instances
        assertNotSame(defaultContext, trustAllContext, "Contexts should be different instances");
    }

    @Test
    public void testTrustAllContextTrustsEverything() throws Exception {
        // Given
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();

        // We can't easily test the actual trust behavior without setting up a full SSL connection,
        // but we can verify that the context was created with the expected protocol
        // and that it's usable

        // When/Then
        assertNotNull(trustAllContext, "Trust-all context should be created");
        assertEquals("TLS", trustAllContext.getProtocol(), "Should use TLS protocol");

        // Verify the context is functional
        assertNotNull(trustAllContext.getSocketFactory(), "Should have socket factory");
        assertNotNull(trustAllContext.getServerSocketFactory(), "Should have server socket factory");

        // The actual trust-all behavior is tested indirectly through integration tests
        // or by examining the implementation (which uses a TrustManager that accepts all certs)
    }

    @Test
    public void testNullPasswordHandling() {
        // Test that null passwords are handled gracefully
        try {
            // This will fail due to file not found, but should handle null password
            // gracefully
            SslUtils.createSslContextWithTruststore("/nonexistent/truststore.jks", null, // null password
                    "JKS");
            fail("Should fail due to file not found, not due to null password");
        } catch (Exception e) {
            // Should be file-related exception, not null pointer exception
            assertFalse(e instanceof NullPointerException, "Should not be NullPointerException");
            assertTrue(
                    e instanceof java.io.FileNotFoundException || e.getCause() instanceof java.io.FileNotFoundException
                            || e.getMessage().contains("FileNotFoundException")
                            || e.getMessage().contains("No such file"),
                    "Should be file-related exception");
        }
    }

    @Test
    public void testEmptyPasswordHandling() {
        // Test that empty passwords are handled gracefully
        try {
            // This will fail due to file not found, but should handle empty password
            // gracefully
            SslUtils.createSslContextWithTruststore("/nonexistent/truststore.jks", "", // empty password
                    "JKS");
            fail("Should fail due to file not found, not due to empty password");
        } catch (Exception e) {
            // Should be file-related exception, not related to password
            assertTrue(
                    e instanceof java.io.FileNotFoundException || e.getCause() instanceof java.io.FileNotFoundException
                            || e.getMessage().contains("FileNotFoundException")
                            || e.getMessage().contains("No such file"),
                    "Should be file-related exception");
        }
    }

    @Test
    public void testSslContextConfiguration() throws Exception {
        // Test that SSL contexts are properly configured
        // Default context
        SSLContext defaultContext = SslUtils.createDefaultSslContext();
        verifyBasicSslContextProperties(defaultContext);
        // Trust-all context
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
        verifyBasicSslContextProperties(trustAllContext);
    }

    private void verifyBasicSslContextProperties(SSLContext sslContext) {
        assertNotNull(sslContext, "SSL context should not be null");
        assertNotNull(sslContext.getSocketFactory(), "Should have socket factory");
        assertNotNull(sslContext.getServerSocketFactory(), "Should have server socket factory");
        // Verify the context has reasonable default parameters
        assertNotNull(sslContext.getDefaultSSLParameters(), "Should have default parameters");
    }
}
