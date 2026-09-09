package org.netxms.launcher;

import org.netxms.client.NXCSession;
import org.netxms.client.TwoFactorAuthenticationCallback;
import org.netxms.client.constants.AuthenticationType;

import java.util.List;
import java.util.Objects;

public final class NxcpSessionService implements SessionService {
    public static final int TOKEN_VALIDITY_SECONDS = 600;

    @Override
    public ServerConnection open(String host, int port) throws SessionException {
        NXCSession session = new NXCSession(host, port);
        session.setIgnoreProtocolVersion(true);
        try {
            session.connect();
        } catch (Exception e) {
            throw LoginErrorMapper.map(e);
        }
        return new NxcpConnection(session);
    }

    private static final class NxcpConnection implements ServerConnection {
        private final NXCSession session;
        private boolean closed;

        NxcpConnection(NXCSession session) {
            this.session = session;
        }

        @Override
        public String serverVersion() {
            checkOpen();
            return session.getServerVersion();
        }

        @Override
        public void login(String user, String password, TwoFactorPrompt prompt) throws SessionException {
            checkOpen();
            Objects.requireNonNull(prompt, "two-factor prompt is required");
            try {
                session.login(AuthenticationType.PASSWORD, user, password, null, null, new TwoFactorCallbackBridge(prompt));
            } catch (Exception e) {
                throw LoginErrorMapper.map(e);
            }
        }

        @Override
        public boolean isPasswordExpired() {
            checkOpen();
            return session.isPasswordExpired();
        }

        @Override
        public int graceLogins() {
            checkOpen();
            return session.getGraceLogins();
        }

        @Override
        public void changePassword(String oldPassword, String newPassword) throws SessionException {
            checkOpen();
            try {
                session.setUserPassword(session.getUserId(), newPassword, oldPassword);
            } catch (Exception e) {
                throw LoginErrorMapper.map(e);
            }
        }

        @Override
        public String requestToken() throws SessionException {
            checkOpen();
            String token;
            try {
                token = session.requestAuthenticationToken(false, TOKEN_VALIDITY_SECONDS, null, 0).getValue();
            } catch (Exception e) {
                throw LoginErrorMapper.map(e);
            }

            if ((token == null) || token.trim().isEmpty()) {
                throw new SessionException(SessionException.Kind.PROTOCOL_FAILURE, "Server did not return an authentication token");
            }
            return token;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            session.disconnect();
        }

        private void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Server connection is closed and cannot be reused");
            }
        }
    }

    static final class TwoFactorCallbackBridge implements TwoFactorAuthenticationCallback {
        private final TwoFactorPrompt prompt;

        TwoFactorCallbackBridge(TwoFactorPrompt prompt) {
            this.prompt = prompt;
        }

        @Override
        public int selectMethod(List<String> methods) {
            return prompt.selectMethod((methods != null) ? methods : List.of());
        }

        @Override
        public String getUserResponse(String challenge, String qrLabel, boolean trustedDevicesAllowed) {
            return prompt.enterCode(challenge, qrLabel);
        }

        @Override
        public void saveTrustedDeviceToken(long serverId, String username, byte[] token) {
        }

        @Override
        public byte[] getTrustedDeviceToken(long serverId, String username) {
            return null;
        }
    }
}
