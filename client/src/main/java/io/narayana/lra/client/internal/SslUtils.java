/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Utility class for creating and configuring SSL contexts for LRA client communication.
 */
public class SslUtils {

    /**
     * Creates an SSL context that trusts all certificates.
     * WARNING: This should only be used for development/testing environments.
     *
     * @return SSLContext that accepts all certificates
     * @throws Exception if SSL context creation fails
     */
    public static SSLContext createTrustAllSslContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");

        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        // Trust all client certificates
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        // Trust all server certificates
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                }
        };

        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }

    /**
     * Creates an SSL context with a custom truststore.
     *
     * @param trustStorePath path to the truststore file
     * @param trustStorePassword password for the truststore (may be null)
     * @param trustStoreType type of truststore (JKS, PKCS12, etc.)
     * @return configured SSLContext
     * @throws Exception if SSL context creation fails
     */
    public static SSLContext createSslContextWithTruststore(
            String trustStorePath,
            String trustStorePassword,
            String trustStoreType) throws Exception {

        KeyStore trustStore = KeyStore.getInstance(trustStoreType);
        try (InputStream trustStoreStream = new FileInputStream(trustStorePath)) {
            trustStore.load(trustStoreStream,
                    trustStorePassword != null ? trustStorePassword.toCharArray() : null);
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    /**
     * Creates an SSL context with both truststore and keystore for mutual TLS authentication.
     *
     * @param trustStorePath path to the truststore file
     * @param trustStorePassword password for the truststore (may be null)
     * @param trustStoreType type of truststore
     * @param keyStorePath path to the keystore file
     * @param keyStorePassword password for the keystore
     * @param keyStoreType type of keystore
     * @return configured SSLContext for mutual TLS
     * @throws Exception if SSL context creation fails
     */
    public static SSLContext createMutualTlsSslContext(
            String trustStorePath, String trustStorePassword, String trustStoreType,
            String keyStorePath, String keyStorePassword, String keyStoreType) throws Exception {

        // Configure trust managers
        KeyStore trustStore = KeyStore.getInstance(trustStoreType);
        try (InputStream trustStoreStream = new FileInputStream(trustStorePath)) {
            trustStore.load(trustStoreStream,
                    trustStorePassword != null ? trustStorePassword.toCharArray() : null);
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // Configure key managers
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        try (InputStream keyStoreStream = new FileInputStream(keyStorePath)) {
            keyStore.load(keyStoreStream,
                    keyStorePassword != null ? keyStorePassword.toCharArray() : null);
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore,
                keyStorePassword != null ? keyStorePassword.toCharArray() : null);

        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(),
                trustManagerFactory.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    /**
     * Creates a simple SSL context using the default system truststore.
     *
     * @return SSLContext using system default trust settings
     * @throws Exception if SSL context creation fails
     */
    public static SSLContext createDefaultSslContext() throws Exception {
        return SSLContext.getDefault();
    }
}
