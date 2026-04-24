/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.arquillian.resource.JwtParticipant;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.util.logging.Logger;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Integration tests verifying role-based access control via {@code @RolesAllowed}.
 * Tests that the container enforces role checks when MP JWT is configured and
 * {@code resteasy.role.based.security=true} is set.
 *
 * <p>
 * Requires the {@code -Parq} Maven profile to start WildFly containers.
 */
public class RbacIntegrationIT extends TestBase {

    private static final Logger log = Logger.getLogger(RbacIntegrationIT.class.getName());

    @ArquillianResource
    public URL baseURL;

    private String testName;

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(RbacIntegrationIT.class.getSimpleName(), JwtParticipant.class);
    }

    @BeforeAll
    public static void setupKeys() throws Exception {
        TestKeyManager.generateKeyPair();
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Test
    public void testTokenWithCorrectRoleIsAccepted() throws Exception {
        String token = TestKeyManager.createSignedToken("admin-user", JwtParticipant.TEST_ROLE);

        try (Response response = client.target(baseURL.toExternalForm())
                .path(JwtParticipant.ROOT_PATH)
                .path(JwtParticipant.ADMIN_ONLY_PATH)
                .request()
                .header("Authorization", "Bearer " + token)
                .get()) {
            assertEquals(200, response.getStatus(),
                    "Token with the required role should be accepted");
            assertEquals("admin-access-granted", response.readEntity(String.class));
        }
    }

    @Test
    public void testTokenWithWrongRoleIsRejected() throws Exception {
        String token = TestKeyManager.createSignedToken("regular-user", "some-other-role");

        try (Response response = client.target(baseURL.toExternalForm())
                .path(JwtParticipant.ROOT_PATH)
                .path(JwtParticipant.ADMIN_ONLY_PATH)
                .request()
                .header("Authorization", "Bearer " + token)
                .get()) {
            assertEquals(403, response.getStatus(),
                    "Token without the required role should be rejected with 403 Forbidden");
        }
    }

    @Test
    public void testNoTokenOnProtectedEndpointIsRejected() throws Exception {
        try (Response response = client.target(baseURL.toExternalForm())
                .path(JwtParticipant.ROOT_PATH)
                .path(JwtParticipant.ADMIN_ONLY_PATH)
                .request()
                .get()) {
            int status = response.getStatus();
            assertTrue(status == 401 || status == 403,
                    "Request without token to @RolesAllowed endpoint should return 401 or 403, got " + status);
        }
    }
}
