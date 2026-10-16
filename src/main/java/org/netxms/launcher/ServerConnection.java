package org.netxms.launcher;

public interface ServerConnection extends AutoCloseable {
    String serverVersion();

    void login(String user, String password, TwoFactorPrompt prompt) throws SessionException;

    boolean isPasswordExpired();

    int graceLogins();

    void changePassword(String oldPassword, String newPassword) throws SessionException;

    String requestToken() throws SessionException;

    @Override
    void close();
}
