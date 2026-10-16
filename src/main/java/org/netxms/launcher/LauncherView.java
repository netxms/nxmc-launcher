package org.netxms.launcher;

public interface LauncherView extends TwoFactorPrompt {
    void showProgress(String message);

    void showDownloadProgress(String version, long downloaded, long total);

    boolean confirmDownload(String branch, ServerVersion version, String manifestHost);

    boolean confirmUpdate(String branch, ServerVersion cached, ReleaseManifest.Release release);

    String promptNewPassword(int graceLogins, String rejection);

    boolean confirmRedownload(LaunchFailure failure);

    void showFailure(String summary, String detail);

    void showWarning(String message);

    void showDiagnostic(String message);

    void launchSucceeded();
}
