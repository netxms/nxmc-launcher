package org.netxms.launcher;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class ConnectFlow {
    static final String DOWNLOADS_PAGE = "https://netxms.org/download";

    static final String IMPORT_HINT = " Alternatively, add the build from a file in Settings if you already have the jar.";

    static final Duration UPDATE_CHECK_INTERVAL = Duration.ofHours(24);
    private final SessionService sessions;
    private final ManifestSource manifests;
    private final PackageManager packages;
    private final AppRunner runner;
    private final ServerRegistry registry;
    private final LauncherView view;
    private final LauncherSettings settings;
    private final Clock clock;
    private boolean registryWarned;

    public ConnectFlow(SessionService sessions, ManifestSource manifests, PackageManager packages, AppRunner runner, ServerRegistry registry, LauncherView view, LauncherSettings settings) {
        this(sessions, manifests, packages, runner, registry, view, settings, Clock.systemUTC());
    }

    ConnectFlow(SessionService sessions, ManifestSource manifests, PackageManager packages, AppRunner runner, ServerRegistry registry, LauncherView view, LauncherSettings settings, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.view = Objects.requireNonNull(view, "view");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static String summary(PackageException e) {
        return switch (e.kind()) {
            case DOWNLOAD_FAILED -> "The nxmc build could not be downloaded.";
            case HASH_MISMATCH -> "The downloaded nxmc build does not match the published checksum.";
            case LOCKED_BY_ANOTHER_LAUNCHER -> "Another launcher instance is working on this nxmc build.";
            case CACHE_ERROR -> "The local nxmc build cache could not be read or written.";
            default -> e.getMessage();
        };
    }

    private static String summary(ManifestException e) {
        return switch (e.kind()) {
            case FETCH_FAILED -> "The release manifest could not be retrieved.";
            case MALFORMED -> "The release manifest could not be read.";
            case UNTRUSTED_URL -> "The release manifest points at a download the launcher does not trust.";
        };
    }

    private static boolean sameBuild(PackageManager.CachedPackage chosen, PackageManager.CachedPackage current) {
        return chosen.version().full().equals(current.version().full()) && Objects.equals(chosen.sha256(), current.sha256());
    }

    private static void closeQuietly(ServerConnection connection) {
        if (connection != null) {
            connection.close();
        }
    }

    private static void closeQuietly(PackageManager.Pin pin) {
        if (pin != null) {
            pin.close();
        }
    }

    public Outcome connect(ServerEntry server, String login, String password) {
        return connect(server, login, password, CancelToken.NONE);
    }

    public Outcome connect(ServerEntry server, String login, String password, CancelToken cancel) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(cancel, "cancel");

        view.showProgress("Connecting to " + server.displayAddress());

        ServerConnection connection = null;
        PackageManager.Pin pin = null;
        try {
            try {
                connection = sessions.open(server.address(), server.port());
            } catch (SessionException e) {
                return report(e);
            }

            String reportedVersion = connection.serverVersion();
            Optional<ServerVersion> parsed = ServerVersion.parse(reportedVersion);
            if (parsed.isEmpty()) {
                view.showFailure("Server " + server.displayAddress() + " reports a version the launcher cannot interpret.", "Server " + server.displayAddress() + " reports version \"" + reportedVersion + "\", which the launcher cannot interpret, so it cannot tell which nxmc build to start.");
                return Outcome.STOPPED;
            }

            ServerVersion version = parsed.get();
            if (!version.isSupported()) {
                view.showFailure("Server " + server.displayAddress() + " is older than the launcher supports.", "Server " + server.displayAddress() + " reports version " + version.full() + ". Servers before NetXMS " + ServerVersion.MINIMUM_SUPPORTED_MAJOR + ".0.0 cannot issue the authentication token the launcher hands to nxmc, so use the standard nxmc client for this server.");
                return Outcome.STOPPED;
            }

            String branch = version.branchKey();

            recordSeen(server, reportedVersion);

            if (cancelled(cancel)) {
                return Outcome.STOPPED;
            }

            PackageManager.CachedPackage build;
            try {
                Optional<PackageManager.CachedPackage> cached = packages.cached(branch);

                Optional<UpdateCheck> check = Optional.empty();
                if (cached.isPresent() && checkDue(cached.get())) {
                    check = Optional.of(availableUpdate(cached.get()));
                }

                if (cancelled(cancel)) {
                    return Outcome.STOPPED;
                }

                Optional<ReleaseManifest.Release> published = Optional.empty();
                if (check.isPresent()) {
                    published = check.get().newer();
                    stampChecked(cached.get(), check.get().answered());
                }

                if (cached.isPresent() && published.isEmpty()) {
                    build = cached.get();
                } else {
                    connection.close();
                    connection = null;

                    if (published.isPresent()) {
                        build = view.confirmUpdate(branch, cached.get().version(), published.get()) ? download(branch, published.get(), cancel) : cached.get();
                    } else {
                        Optional<PackageManager.CachedPackage> installed = install(branch, version, cancel);
                        if (installed.isEmpty()) {
                            return Outcome.STOPPED;
                        }
                        build = installed.get();
                    }

                    view.showProgress("Connecting to " + server.displayAddress());
                    connection = sessions.open(server.address(), server.port());
                }
            } catch (PackageException e) {
                if (e.kind() == PackageException.Kind.CANCELLED) {
                    view.showProgress("Cancelled.");
                } else {
                    view.showFailure(summary(e), e.getMessage());
                }
                return Outcome.STOPPED;
            } catch (ManifestException e) {
                boolean nothingToDownload = e.kind() == ManifestException.Kind.FETCH_FAILED || e.kind() == ManifestException.Kind.MALFORMED;
                view.showFailure(summary(e), nothingToDownload ? e.getMessage() + "." + IMPORT_HINT : e.getMessage());
                return Outcome.STOPPED;
            } catch (SessionException e) {
                return report(e);
            }

            try {
                packages.markUsed(branch);
            } catch (PackageException ignored) {
            }
            try {
                pin = packages.pin(branch).orElse(null);
            } catch (PackageException e) {
                view.showFailure(summary(e), e.kind() == PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER ? e.getMessage() + ". Connect again once it has finished." : e.getMessage());
                return Outcome.STOPPED;
            }

            Optional<PackageManager.CachedPackage> pinned = packages.cached(branch);
            if (pinned.isEmpty() || !sameBuild(build, pinned.get())) {
                view.showFailure("The cached nxmc build for branch " + branch + " was removed or replaced.", "The cached nxmc build for branch " + branch + " was removed or replaced by another launcher instance" + " before it could be started. Connect again to use the build now in the cache.");
                return Outcome.STOPPED;
            }

            if (cancelled(cancel)) {
                return Outcome.STOPPED;
            }

            try {
                view.showProgress("Logging in as " + login);
                connection.login(login, password, view);

                if (connection.isPasswordExpired()) {
                    Optional<Outcome> interrupted = changeExpiredPassword(connection, password);
                    if (interrupted.isPresent()) {
                        return interrupted.get();
                    }
                }
            } catch (SessionException e) {
                return report(e);
            }

            String token;
            try {
                view.showProgress("Requesting authentication token");
                token = connection.requestToken();
            } catch (SessionException e) {
                view.showFailure("Server did not issue an authentication token.", "Server did not issue an authentication token: " + e.getMessage());
                return Outcome.STOPPED;
            }

            connection.close();
            connection = null;

            PackageManager.Pin held = pin;
            pin = null;
            return launch(server, login, reportedVersion, build, token, held);
        } finally {
            closeQuietly(connection);
            closeQuietly(pin);
        }
    }

    private boolean checkDue(PackageManager.CachedPackage cached) {
        if (cached.updateArmed()) {
            return true;
        }

        if (!settings.checkForUpdates().orElse(Boolean.FALSE)) {
            return false;
        }

        Optional<Instant> last = cached.lastChecked();
        return last.isEmpty() || !last.get().plus(UPDATE_CHECK_INTERVAL).isAfter(clock.instant());
    }

    private void stampChecked(PackageManager.CachedPackage cached, boolean answered) {
        try {
            if (answered) {
                packages.markChecked(cached.branch(), cached.checkStamp());
            } else {
                packages.markCheckFailed(cached.branch());
            }
        } catch (PackageException ignored) {
        }
    }

    private UpdateCheck availableUpdate(PackageManager.CachedPackage cached) throws ManifestException {
        try {
            view.showProgress("Checking for a newer nxmc build");
            return new UpdateCheck(packages.updateAvailable(cached, manifests.fetch()), true);
        } catch (ManifestException e) {
            if (e.kind() == ManifestException.Kind.UNTRUSTED_URL) {
                throw e;
            }
            return new UpdateCheck(Optional.empty(), false);
        }
    }

    private Optional<PackageManager.CachedPackage> install(String branch, ServerVersion version, CancelToken cancel) throws ManifestException, PackageException {
        if (!view.confirmDownload(branch, version, manifests.host())) {
            view.showProgress("");
            return Optional.empty();
        }

        view.showProgress("Looking up the nxmc build for server version " + version.full());
        ReleaseManifest manifest = manifests.fetch();

        if (cancelled(cancel)) {
            return Optional.empty();
        }

        Optional<ReleaseManifest.Release> release = manifest.release(branch);

        if (release.isEmpty()) {
            view.showFailure("The release manifest has no nxmc build for server branch " + branch + ".", "The release manifest at " + manifest.source() + " has no nxmc build for server branch " + branch + ". See " + DOWNLOADS_PAGE + " for available downloads." + IMPORT_HINT);
            return Optional.empty();
        }

        return Optional.of(download(branch, release.get(), cancel));
    }

    private PackageManager.CachedPackage download(String branch, ReleaseManifest.Release release, CancelToken cancel) throws PackageException {
        view.showProgress("Downloading nxmc " + release.version());
        return packages.download(branch, release, (downloaded, total) -> view.showDownloadProgress(release.version(), downloaded, total), cancel);
    }

    private boolean cancelled(CancelToken cancel) {
        if (!cancel.cancelled()) {
            return false;
        }

        view.showProgress("Cancelled.");
        return true;
    }

    private Optional<Outcome> changeExpiredPassword(ServerConnection connection, String currentPassword) {
        int graceLogins = connection.graceLogins();
        String rejection = null;
        while (true) {
            String newPassword = view.promptNewPassword(graceLogins, rejection);
            if (newPassword == null) {
                return Optional.empty();
            }

            try {
                connection.changePassword(currentPassword, newPassword);
                return Optional.empty();
            } catch (SessionException e) {
                if (e.kind() != SessionException.Kind.PASSWORD_REJECTED) {
                    return Optional.of(report(e));
                }

                rejection = e.getMessage();
                view.showFailure(e.getMessage(), e.getMessage());
            }
        }
    }

    private Outcome launch(ServerEntry server, String login, String reportedVersion, PackageManager.CachedPackage build, String token, PackageManager.Pin pin) {
        view.showProgress("Starting nxmc " + build.version().full());

        LaunchFailure failure = null;
        try {
            runner.run(build.jar(), server.address(), server.port(), token);
        } catch (LaunchFailure e) {
            failure = e;
        } finally {
            closeQuietly(pin);
        }

        if (failure != null) {
            return launchFailed(build, failure);
        }

        remember(server, login, reportedVersion);
        view.launchSucceeded();
        return Outcome.LAUNCHED;
    }

    private Outcome launchFailed(PackageManager.CachedPackage build, LaunchFailure failure) {
        if ((failure.kind() == LaunchFailure.Kind.EARLY_EXIT) && view.confirmRedownload(failure)) {
            try {
                packages.delete(build.branch());
                view.showProgress("");
                return Outcome.RETRY_LOGIN;
            } catch (PackageException e) {
                view.showFailure(summary(e), e.getMessage());
                return Outcome.STOPPED;
            }
        }

        String tail = failure.stderrTail();
        view.showDiagnostic(tail.isBlank() ? failure.getMessage() : (failure.getMessage() + "\n\n" + tail));
        return Outcome.STOPPED;
    }

    private void recordSeen(ServerEntry server, String reportedVersion) {
        store(server, entry -> entry.withLastSeenVersion(reportedVersion).withLastUsed(clock.instant()));
    }

    private void remember(ServerEntry server, String login, String reportedVersion) {
        store(server, entry -> entry.withLastLogin(login).withLastSeenVersion(reportedVersion).withLastUsed(clock.instant()));
    }

    private void store(ServerEntry server, UnaryOperator<ServerEntry> update) {
        registry.addOrUpdate(update.apply(registry.find(server.address(), server.port()).orElse(server)));
        try {
            registry.save();
        } catch (IOException e) {
            if (registryWarned) {
                return;
            }

            registryWarned = true;
            view.showWarning("Cannot update the server list " + registry.file() + ": " + e.getMessage());
        }
    }

    private Outcome report(SessionException e) {
        switch (e.kind()) {
            case CANCELLED:
                view.showProgress("");
                return Outcome.RETRY_LOGIN;
            case UNREACHABLE:
            case BAD_CREDENTIALS:
            case BAD_TWO_FACTOR_CODE:
                view.showFailure(e.getMessage(), e.getMessage());
                return Outcome.RETRY_LOGIN;
            case NO_GRACE_LOGINS:
                view.showFailure("Your password has expired and there are no grace logins left.", "Your password has expired and there are no grace logins left: " + e.getMessage() + ". A NetXMS administrator has to reset it.");
                return Outcome.STOPPED;
            default:
                view.showFailure("The connection to the server failed.", e.getMessage());
                return Outcome.STOPPED;
        }
    }

    public enum Outcome {
        LAUNCHED,
        RETRY_LOGIN,
        STOPPED
    }

    public interface ManifestSource {
        String host();

        ReleaseManifest fetch() throws ManifestException;
    }

    private record UpdateCheck(Optional<ReleaseManifest.Release> newer, boolean answered) {
    }
}
