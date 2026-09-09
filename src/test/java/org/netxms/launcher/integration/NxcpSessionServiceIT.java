package org.netxms.launcher.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.netxms.launcher.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real protocol against a development server. Runs only under
 * {@code mvn verify -Pintegration -Dnetxms.test.server=host[:port] -Dnetxms.test.user=... -Dnetxms.test.password=...};
 * skipped when those properties are not set. The account must not require two-factor authentication.
 */
class NxcpSessionServiceIT {
    private static final int DEFAULT_PORT = 4701;
    private static final TwoFactorPrompt FAIL_ON_2FA = new TwoFactorPrompt() {
        @Override
        public int selectMethod(List<String> methods) {
            throw new AssertionError("test account must not require two-factor authentication");
        }

        @Override
        public String enterCode(String challenge, String qrLabel) {
            throw new AssertionError("test account must not require two-factor authentication");
        }
    };
    private static String host;
    private static int port = DEFAULT_PORT;
    private static String user;
    private static String password;

    @BeforeAll
    static void readConfiguration() {
        String server = System.getProperty("netxms.test.server", "").trim();
        user = System.getProperty("netxms.test.user", "").trim();
        password = System.getProperty("netxms.test.password", "");
        Assumptions.assumeTrue(!server.isEmpty() && !user.isEmpty() && !password.isEmpty(), "netxms.test" + ".server" +
                "/user/password not set");

        int separator = server.lastIndexOf(':');
        if (separator > 0) {
            host = server.substring(0, separator);
            port = Integer.parseInt(server.substring(separator + 1));
        } else {
            host = server;
        }
    }

    @Test
    void readsServerVersionBeforeLogin() throws Exception {
        try (ServerConnection connection = new NxcpSessionService().open(host, port)) {
            String version = connection.serverVersion();
            assertFalse(version.trim().isEmpty(), "server reported no version");
            assertTrue(ServerVersion.parse(version).isPresent(), "unparseable server version: " + version);
        }
    }

    @Test
    void logsInAndIssuesHandoffToken() throws Exception {
        try (ServerConnection connection = new NxcpSessionService().open(host, port)) {
            connection.login(user, password, FAIL_ON_2FA);
            assertFalse(connection.isPasswordExpired(), "test account password is expired");

            String token = connection.requestToken();
            assertFalse(token.trim().isEmpty(), "server returned an empty token");
        }
    }

    @Test
    void rejectsBadCredentials() throws Exception {
        try (ServerConnection connection = new NxcpSessionService().open(host, port)) {
            SessionException e = assertThrows(SessionException.class, () -> connection.login(user, password +
                    "-definitely-wrong", FAIL_ON_2FA));
            assertEquals(SessionException.Kind.BAD_CREDENTIALS, e.kind());
        }
    }

    @Test
    void reportsUnreachableServer() {
        SessionException e = assertThrows(SessionException.class, () -> new NxcpSessionService().open("127.0.0.1", 1));
        assertEquals(SessionException.Kind.UNREACHABLE, e.kind());
    }
}
