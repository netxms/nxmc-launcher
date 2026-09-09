package org.netxms.launcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ConnectFlowTest {
    private static final String SHA256 = "0".repeat(64);
    private static final URI MANIFEST_URL = URI.create("https://netxms.org/nxmc-releases.json");
    private static final ServerEntry SERVER = ServerEntry.of("nx.example.com", 4701);
    private static final Instant NOW = Instant.parse("2026-07-24T10:15:30Z");
    /**
     * Deliberately unfit for a status line, so a kind that passes its message through fails here.
     */
    private static final String UNFIT_DETAIL = "the long detail nobody has to read to know what went wrong, on a " + "second line as well\nand a third";
    @TempDir
    private Path home;
    private List<String> events;
    private FakeView view;
    private FakeSessions sessions;
    private FakePackages packages;
    private FakeRunner runner;
    private ServerRegistry registry;
    private FakeManifests manifests;
    private ReleaseManifest manifest;
    private ManifestException manifestFailure;
    private String manifestHost;
    private LauncherSettings settings;
    private Clock clock;

    /**
     * A status line holds one sentence: short, and not the multi-line text a box would take.
     */
    private static void assertOneSentence(String summary) {
        assertFalse(summary.isBlank(), "a failure with nothing to say is not a failure");
        assertFalse(summary.contains("\n"), summary);
        assertTrue(summary.length() <= 80, "too long for the status line: " + summary);
    }

    private static ReleaseManifest manifest(String branch, String version, String url) throws ManifestException {
        return ReleaseManifest.parse(MANIFEST_URL, manifestJson(branch, version, url));
    }

    private static String manifestJson(String branch, String version, String url) {
        return "{\"releases\":{\"" + branch + "\":{\"version\":\"" + version + "\",\"url\":\"" + url + "\",\"sha256" + "\":\"" + SHA256 + "\",\"size\":1024}}}";
    }

    @BeforeEach
    void setUp() throws Exception {
        events = new ArrayList<>();
        view = new FakeView();
        sessions = new FakeSessions(events);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        packages = new FakePackages(home, events, clock);
        runner = new FakeRunner(events);
        registry = ServerRegistry.load(home);
        manifests = new FakeManifests();
        manifest = manifest("5.2", "5.2.3", "https://netxms.org/nxmc-5.2.3.jar");
        manifestFailure = null;
        manifestHost = "netxms.org";
        settings = LauncherSettings.load(home);
        settings.setCheckForUpdates(true);
    }

    private ConnectFlow flow() {
        return new ConnectFlow(sessions, manifests, packages, runner, registry, view, settings, clock);
    }

    @Test
    void cacheHitLaunchesOverASingleConnection() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "manifest",
                "markChecked:5.2",
                "markUsed:5.2",
                "pin:5.2",
                "c1:login:admin",
                "c1:token",
                "c1:close",
                "spawn:cached.jar:nx.example.com:4701:TOKEN-1",
                "unpin:5.2"
        ), events);
        assertEquals(1, connection.closes);
        assertTrue(view.launched);
        assertTrue(view.failures.isEmpty());
    }

    @Test
    void cacheMissDownloadsThenLoginsOverAFreshConnection() {
        FakeConnection probe = sessions.enqueue("c1");
        FakeConnection session = sessions.enqueue("c2");
        packages.downloaded = build("5.2", "5.2.3", "downloaded.jar");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "c1:close",
                "manifest",
                "download:5.2:5.2.3",
                "open:nx.example.com:4701",
                "markUsed:5.2",
                "pin:5.2",
                "c2:login:admin",
                "c2:token",
                "c2:close",
                "spawn:downloaded.jar:nx.example.com:4701:TOKEN-1",
                "unpin:5.2"
        ), events);
        assertNotSame(probe, session);
        assertEquals(1, probe.closes);
        assertEquals(1, session.closes);
        assertEquals("5.2", view.askedBranch);
        assertEquals("5.2.3", view.askedVersion.full());
        assertEquals("netxms.org", view.askedManifestHost);
    }

    @Test
    void anAcceptedDownloadFetchesOnceAndAsksNothingElse() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.downloaded = build("5.2", "5.2.3", "downloaded.jar");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, manifests.fetches, events.toString());
        assertEquals(1, view.downloadQuestions);
    }

    @Test
    void aDeclinedDownloadNeverReachesTheNetwork() {
        sessions.enqueue("c1");
        view.confirmDownload = false;

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(0, manifests.fetches, "the manifest must not be fetched before the user consents");
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "c1:close"
        ), events);
        assertEquals("", lastProgress(), view.progress.toString());
    }

    @Test
    void anUnusableManifestUrlStillNamesSomethingInTheQuestion() {
        sessions.enqueue("c1");
        manifestHost = "http://a b/x";
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "Invalid " + ManifestClient.MANIFEST_URL_PROPERTY + " value: http://a b/x");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals("http://a b/x", view.askedManifestHost);
        assertEquals("The release manifest could not be retrieved.", view.onlyFailure().summary());
        assertTrue(view.onlyFailure().detail().contains("http://a b/x"), view.onlyFailure().detail());
    }

    @Test
    void successfulLaunchRemembersServerInRegistry() throws Exception {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        flow().connect(SERVER, "admin", "secret");

        ServerEntry stored = ServerRegistry.load(home).find(SERVER.address(), SERVER.port()).orElseThrow();
        assertEquals("admin", stored.lastLogin());
        assertEquals("5.2.3", stored.lastSeenVersion());
        assertEquals(NOW.toString(), stored.lastUsed());
    }

    @Test
    void versionProbeEntersTheRegistryEvenWhenTheLoginFails() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.BAD_CREDENTIALS, "access denied");

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "wrong"));

        ServerEntry stored = ServerRegistry.load(home).find(SERVER.address(), SERVER.port()).orElseThrow();
        assertEquals("5.2.3", stored.lastSeenVersion());
        assertEquals(NOW.toString(), stored.lastUsed());
        assertNull(stored.lastLogin(), "a login that never worked must not be offered as a prefill");
    }

    @Test
    void unparseableVersionLeavesTheServerOutOfTheRegistry() {
        sessions.enqueue("c1").version = "not a version";

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(ServerRegistry.load(home).find(SERVER.address(), SERVER.port()).isEmpty());
    }

    @Test
    void failedReloginKeepsThePreviouslyStoredLogin() throws Exception {
        registry.addOrUpdate(SERVER.withLastLogin("admin").withLastSeenVersion("5.1.2").withLastUsed(NOW.minusSeconds(86400)));
        registry.save();

        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.BAD_CREDENTIALS, "access denied");

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "wrong"));

        ServerEntry stored = ServerRegistry.load(home).find(SERVER.address(), SERVER.port()).orElseThrow();
        assertEquals("admin", stored.lastLogin());
        assertEquals("5.2.3", stored.lastSeenVersion());
        assertEquals(NOW.toString(), stored.lastUsed());
    }

    @Test
    void anIpv6ServerOnANonDefaultPortIsHandedOverUnguarded() {
        ServerEntry ipv6 = ServerEntry.of("fe80::1", 1234);
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(ipv6, "admin", "secret"));
        assertTrue(events.contains("spawn:cached.jar:fe80::1:1234:TOKEN-1"), events.toString());
        assertTrue(view.launched);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
    }

    @Test
    void unparseableServerVersionStopsAndShowsRawString() {
        FakeConnection connection = sessions.enqueue("c1");
        connection.version = "custom-build";

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertFalse(failure.summary().contains("custom-build"), failure.summary());
        assertTrue(failure.detail().contains("custom-build"), failure.detail());
        assertEquals(1, connection.closes);
        assertNoLoginAndNoLaunch();
    }

    @Test
    void aPre50ServerStopsBeforeAnythingIsSpent() {
        FakeConnection connection = sessions.enqueue("c1");
        connection.version = "4.5.7";

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertOneSentence(failure.summary());
        assertFalse(failure.summary().contains("4.5.7"), failure.summary());
        assertTrue(failure.detail().contains("4.5.7"), failure.detail());
        assertTrue(failure.detail().contains("5.0.0"), failure.detail());
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "c1:close"
        ), events);
        assertEquals(1, connection.closes);
        assertNoLoginAndNoLaunch();
    }

    @Test
    void aPre50ServerLeavesTheServerOutOfTheRegistry() {
        sessions.enqueue("c1").version = "4.5.7";

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(ServerRegistry.load(home).find(SERVER.address(), SERVER.port()).isEmpty());
    }

    @Test
    void a500ServerIsOnTheSupportedSideOfTheFloor() {
        sessions.enqueue("c1").version = "5.0.0";
        packages.cached = Optional.of(build("5.0", "5.0.0", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.launched);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
    }

    @Test
    void missingBranchStopsWithDownloadsHint() throws Exception {
        sessions.enqueue("c1");
        manifest = manifest("5.1", "5.1.7", "https://netxms.org/nxmc-5.1.7.jar");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertTrue(failure.summary().contains("5.2"), failure.summary());
        assertTrue(failure.detail().contains(ConnectFlow.DOWNLOADS_PAGE), failure.detail());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void missingBranchNamesTheManifestThatWasRead() throws Exception {
        sessions.enqueue("c1");
        URI mirror = URI.create("https://mirror.example.com/nxmc-releases.json");
        manifest = ReleaseManifest.parse(mirror, manifestJson("5.1", "5.1.7", "https://mirror.example.com/nxmc-5.1.7" + ".jar"));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertTrue(failure.detail().contains(mirror.toString()), failure.detail());
        assertFalse(failure.detail().contains(ManifestClient.DEFAULT_MANIFEST_URL.toString()), failure.detail());
    }

    @Test
    void missingBranchPointsAtTheSettingsImportInTheDetailOnly() throws Exception {
        sessions.enqueue("c1");
        manifest = manifest("5.1", "5.1.7", "https://netxms.org/nxmc-5.1.7.jar");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertTrue(failure.detail().endsWith(ConnectFlow.IMPORT_HINT), failure.detail());
        assertTrue(failure.detail().contains("Settings"), failure.detail());
        assertTrue(failure.detail().indexOf(ConnectFlow.DOWNLOADS_PAGE) < failure.detail().indexOf("Settings"), failure.detail());
        assertFalse(failure.summary().contains("Settings"), failure.summary());
    }

    @Test
    void untrustedReleaseUrlStopsBeforeAnyDownload() throws Exception {
        sessions.enqueue("c1");
        manifest = manifest("5.2", "5.2.3", "https://mirror.example.com/nxmc-5.2.3.jar");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.onlyFailure().detail().contains("mirror.example.com"), view.failures.toString());
        assertFalse(events.contains("download:5.2:5.2.3"));
        assertNoLoginAndNoLaunch();
    }

    @Test
    void unreachableManifestStopsCacheMiss() {
        sessions.enqueue("c1");
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "manifest host is down");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("manifest host is down." + ConnectFlow.IMPORT_HINT), view.details());
        assertEquals(List.of("The release manifest could not be retrieved."), view.summaries());
        assertFalse(view.onlyFailure().summary().contains("Settings"), view.onlyFailure().summary());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void malformedManifestPointsAtTheSettingsImport() {
        sessions.enqueue("c1");
        manifestFailure = new ManifestException(ManifestException.Kind.MALFORMED, "manifest is not valid JSON");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertEquals("The release manifest could not be read.", failure.summary());
        assertEquals("manifest is not valid JSON." + ConnectFlow.IMPORT_HINT, failure.detail());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void untrustedManifestDetailDoesNotPointAtTheSettingsImport() {
        sessions.enqueue("c1");
        manifestFailure = new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "release URL is not HTTPS");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("release URL is not HTTPS"), view.details());
        assertNoLoginAndNoLaunch();
    }

    /**
     * The air-gapped user, end to end, against the real {@link PackageManager} rather than the fake.
     */
    @Test
    void anImportedBuildLaunchesWithNothingPublishedAndNoNetwork() throws Exception {
        PackageManager real = new PackageManager(home, clock, null);
        PackageManager.CachedPackage imported = real.importBuild(real.identify(standaloneJar("5.2.3")));
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "no route to netxms.org");
        sessions.enqueue("c1");

        Clock later = Clock.fixed(NOW.plus(ConnectFlow.UPDATE_CHECK_INTERVAL).plusSeconds(1), ZoneOffset.UTC);
        ConnectFlow flow = new ConnectFlow(sessions, manifests, real, runner, registry, view, settings, later);

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow.connect(SERVER, "admin", "secret"));
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "manifest",
                "c1:login:admin",
                "c1:token",
                "c1:close",
                "spawn:nxmc-standalone.jar:nx.example.com:4701:TOKEN-1"
        ), events);
        assertEquals(imported.jar(), runner.jar);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertTrue(view.launched);
    }

    @Test
    void checksumMismatchStopsWithoutLaunching() {
        sessions.enqueue("c1");
        packages.downloadFailure = new PackageException(PackageException.Kind.HASH_MISMATCH, "checksum does not match");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("checksum does not match"), view.details());
        assertEquals(List.of("The downloaded nxmc build does not match the published checksum."), view.summaries());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void busyCacheStopsWithoutLaunching() {
        sessions.enqueue("c1");
        packages.downloadFailure = new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, "another " + "launcher instance is downloading");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.failures.size(), view.failures.toString());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void offeredUpdateIsDownloadedOverAFreshConnection() {
        FakeConnection probe = sessions.enqueue("c1");
        FakeConnection session = sessions.enqueue("c2");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        packages.downloaded = build("5.2", "5.2.3", "updated.jar");
        view.confirmUpdate = true;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "manifest",
                "markChecked:5.2",
                "c1:close",
                "download:5.2:5.2.3",
                "open:nx.example.com:4701",
                "markUsed:5.2",
                "pin:5.2",
                "c2:login:admin",
                "c2:token",
                "c2:close",
                "spawn:updated.jar:nx.example.com:4701:TOKEN-1",
                "unpin:5.2"
        ), events);
        assertEquals(1, probe.closes);
        assertEquals(1, session.closes);
    }

    @Test
    void failedUpdateDownloadClosesTheProbeConnection() {
        FakeConnection probe = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        packages.downloadFailure = new PackageException(PackageException.Kind.DOWNLOAD_FAILED, "connection reset");
        view.confirmUpdate = true;

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("connection reset"), view.details());
        assertEquals(1, probe.closes);
        assertNoLoginAndNoLaunch();
    }

    @Test
    void unexpectedViewFailureStillClosesTheConnection() {
        FakeConnection connection = sessions.enqueue("c1");
        connection.passwordExpired = true;
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        view.promptNewPasswordFailure = new IllegalStateException("widget is disposed");

        assertThrows(IllegalStateException.class, () -> flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, connection.closes, "an unchecked failure must not leak the server session");
        assertNull(runner.jar);
    }

    @Test
    void updatePromptRunsWithoutAnOpenServerSession() {
        FakeConnection probe = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        view.confirmUpdateFailure = new IllegalStateException("widget is disposed");

        assertThrows(IllegalStateException.class, () -> flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, probe.closes, "the probe must be gone before the prompt, not left waiting on the user");
        assertNoLoginAndNoLaunch();
    }

    @Test
    void serverLostAfterADownloadKeepsTheLoginPanelOpen() {
        FakeConnection probe = sessions.enqueue("c1");
        sessions.enqueueFailure(new SessionException(SessionException.Kind.UNREACHABLE, "connection refused"));
        packages.downloaded = build("5.2", "5.2.3", "downloaded.jar");

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("connection refused"), view.summaries());
        assertTrue(events.contains("download:5.2:5.2.3"), events.toString());
        assertEquals(1, probe.closes);
        assertNoLoginAndNoLaunch();
    }

    @Test
    void declinedUpdateLaunchesCachedBuildOverAFreshConnection() {
        FakeConnection probe = sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        view.confirmUpdate = false;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertFalse(events.contains("download:5.2:5.2.3"));
        assertEquals("cached.jar", runner.jar.getFileName().toString());
        assertEquals(1, probe.closes);
        assertEquals(List.of(
                "open:nx.example.com:4701",
                "c1:version",
                "manifest",
                "markChecked:5.2",
                "c1:close",
                "open:nx.example.com:4701",
                "markUsed:5.2",
                "pin:5.2",
                "c2:login:admin",
                "c2:token",
                "c2:close",
                "spawn:cached.jar:nx.example.com:4701:TOKEN-1",
                "unpin:5.2"
        ), events);
    }

    @Test
    void unavailableManifestDoesNotBlockCachedLaunch() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "manifest host is down");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty());
        assertEquals("cached.jar", runner.jar.getFileName().toString());
    }

    @Test
    void untrustedUpdateUrlStopsCachedLaunch() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        manifestFailure = new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "release URL is not HTTPS");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("release URL is not HTTPS"), view.details());
        assertEquals(1, connection.closes, "the probe connection must not be left open");
        assertNoLoginAndNoLaunch();
    }

    @Test
    void unreachableServerKeepsLoginPanelOpen() {
        sessions.enqueueFailure(new SessionException(SessionException.Kind.UNREACHABLE, "connection refused"));

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("connection refused"), view.summaries());
        assertTrue(view.diagnostics.isEmpty(), "a server the user can retype is not a diagnostic");
        assertNoLoginAndNoLaunch();
    }

    @Test
    void badCredentialsRetryInPlace() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.BAD_CREDENTIALS, "access denied");

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "wrong"));
        assertEquals(List.of("access denied"), view.summaries());
        assertEquals(1, connection.closes);
        assertFalse(view.launched);
        assertFalse(events.contains("c1:token"));
    }

    @Test
    void protocolFailureDuringLoginStops() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.PROTOCOL_FAILURE, "protocol version " + "mismatch");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("The connection to the server failed."), view.summaries());
        assertEquals(List.of("protocol version mismatch"), view.details());
        assertEquals(1, connection.closes);
        assertFalse(view.launched);
    }

    @Test
    void twoFactorPromptsAreServedByTheView() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.twoFactor = prompt -> {
            events.add("2fa:method=" + prompt.selectMethod(List.of("TOTP", "Message")));
            events.add("2fa:code=" + prompt.enterCode("Enter code", null));
        };

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("2fa:method=0"));
        assertTrue(events.contains("2fa:code=123456"));
    }

    @Test
    void badTwoFactorCodeRetriesInPlace() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.BAD_TWO_FACTOR_CODE, "invalid code");

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("invalid code"), view.summaries());
        assertFalse(view.launched);
    }

    @Test
    void cancelledTwoFactorReturnsToLoginPanelSilently() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.CANCELLED, "cancelled by user");

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty());
        assertTrue(view.diagnostics.isEmpty());
        assertEquals(1, connection.closes);
        assertFalse(view.launched);
    }

    @Test
    void expiredPasswordIsChangedBeforeTokenRequest() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.passwordExpired = true;
        view.newPasswords.add("newSecret");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.indexOf("c1:changePassword:newSecret") < events.indexOf("c1:token"));
    }

    @Test
    void declinedPasswordChangeStillHandsTheSessionOver() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.passwordExpired = true;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty(), "declining is not an error");
        assertFalse(events.contains("c1:changePassword:null"));
        assertTrue(events.contains("spawn:cached.jar:nx.example.com:4701:TOKEN-1"), events.toString());
        assertEquals(1, connection.closes);
        assertTrue(view.launched);
    }

    @Test
    void expiredPasswordPromptIsToldHowManyGraceLoginsAreLeft() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.passwordExpired = true;
        connection.graceLogins = 2;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of(2), view.promptedGraceLogins);
    }

    @Test
    void rejectedNewPasswordIsRetriedInPlace() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.passwordExpired = true;
        connection.changePasswordErrors.add(new SessionException(SessionException.Kind.PASSWORD_REJECTED, "password " + "is too weak"));
        view.newPasswords.add("weak");
        view.newPasswords.add("str0ngEnough!");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("password is too weak"), view.summaries());
        assertTrue(events.contains("c1:changePassword:str0ngEnough!"));
    }

    @Test
    void aRejectedPasswordReopensThePromptWithTheServersReason() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.passwordExpired = true;
        connection.changePasswordErrors.add(new SessionException(SessionException.Kind.PASSWORD_REJECTED, "password " + "is too weak"));
        view.newPasswords.add("weak");
        view.newPasswords.add("str0ngEnough!");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(2, view.promptedRejections.size(), view.promptedRejections.toString());
        assertNull(view.promptedRejections.get(0), "there is nothing to explain on the first ask");
        assertEquals("password is too weak", view.promptedRejections.get(1));
    }

    @Test
    void anAccountOutOfGraceLoginsStopsWithoutOfferingThePasswordAgain() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.loginError = new SessionException(SessionException.Kind.NO_GRACE_LOGINS, "password expired and no grace logins left");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("Your password has expired and there are no grace logins left."), view.summaries());
        assertEquals(List.of("Your password has expired and there are no grace logins left: password expired and no grace logins left. A NetXMS administrator has to reset it."), view.details());
        assertFalse(events.contains("c1:token"));
        assertTrue(view.newPasswords.isEmpty(), "an exhausted account has nothing to change the password with");
    }

    @Test
    void passwordChangeFailingForAnotherReasonStops() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.passwordExpired = true;
        connection.changePasswordErrors.add(new SessionException(SessionException.Kind.PROTOCOL_FAILURE, "server " + "closed the channel"));
        view.newPasswords.add("str0ngEnough!");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("The connection to the server failed."), view.summaries());
        assertEquals(List.of("server closed the channel"), view.details());
        assertEquals(1, connection.closes);
        assertFalse(events.contains("c1:token"));
        assertTrue(view.newPasswords.isEmpty(), "the user must not be prompted again");
    }

    @Test
    void unstampedLastUseDoesNotAbortAnAuthenticatedLaunch() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.markUsedFailure = new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, "another " + "instance holds branch 5.2");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertTrue(view.launched);
    }

    @Test
    void branchAnotherInstanceIsWorkingOnStopsBeforeLogin() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.pinFailure = new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, "another " + "instance holds branch 5.2");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("another instance holds branch 5.2. Connect again once it has finished."), view.details());
        assertFalse(events.contains("c1:login:admin"), events.toString());
        assertFalse(events.contains("c1:token"));
        assertFalse(view.launched);
        assertEquals(1, connection.closes);
    }

    @Test
    void unlockableCacheStopsBeforeLogin() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.pinFailure = new PackageException(PackageException.Kind.CACHE_ERROR, "cannot lock cache directory " + "/cache/5.2");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(List.of("cannot lock cache directory /cache/5.2"), view.details());
        assertFalse(view.launched);
    }

    @Test
    void platformWithoutSharedLocksLaunchesWithoutAPin() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.pinUnavailable = true;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertTrue(view.launched);
        assertFalse(events.contains("unpin:5.2"), events.toString());
    }

    @Test
    void buildEvictedBeforeThePinTookHoldStopsBeforeLogin() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.cachedAfterPin = Optional.empty();

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.failures.size(), view.failures.toString());
        assertFalse(events.contains("c1:login:admin"), events.toString());
        assertFalse(events.contains("c1:token"));
        assertFalse(view.launched);
        assertEquals(1, connection.closes);
    }

    @Test
    void buildReplacedBeforeThePinTookHoldStopsBeforeLogin() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.cachedAfterPin = Optional.of(build("5.2", "5.2.4", "cached.jar", "1".repeat(64)));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.failures.size(), view.failures.toString());
        assertFalse(events.contains("c1:login:admin"), events.toString());
        assertFalse(view.launched);
    }

    @Test
    void jarRebuiltUnderTheSameVersionBeforeThePinTookHoldStopsBeforeLogin() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.cachedAfterPin = Optional.of(build("5.2", "5.2.3", "cached.jar", "1".repeat(64)));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.failures.size(), view.failures.toString());
        assertFalse(events.contains("c1:login:admin"), events.toString());
        assertFalse(view.launched);
    }

    @Test
    void buildStillInPlaceWhenPinnedLaunches() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.cachedAfterPin = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty(), view.failures.toString());
    }

    @Test
    void aCheckStampWrittenBetweenSelectionAndThePinIsNotAReplacement() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(checkedBuild("5.2", "5.2.3", "cached.jar", NOW.minus(Duration.ofHours(25))));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(Optional.of(NOW), packages.cached.orElseThrow().lastChecked(), "the stamp has to have moved for " + "this to prove anything");
        assertTrue(events.indexOf("markChecked:5.2") < events.indexOf("pin:5.2"), events.toString());
        assertTrue(view.failures.isEmpty(), view.failures.toString());
    }

    @Test
    void chosenBuildStaysPinnedWhileTheUserIsBeingAsked() {
        FakeConnection connection = sessions.enqueue("c1");
        connection.loginError = new SessionException(SessionException.Kind.BAD_CREDENTIALS, "wrong password");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "secret"));

        assertEquals(List.of("markUsed:5.2"), events.stream().filter(e -> e.startsWith("markUsed:")).toList());
        assertTrue(events.indexOf("markUsed:5.2") < events.indexOf("pin:5.2"), events.toString());
        assertTrue(events.indexOf("pin:5.2") < events.indexOf("c1:login:admin"), events.toString());
        assertEquals("unpin:5.2", events.get(events.size() - 1), events.toString());
    }

    @Test
    void tokenFailureStops() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        connection.tokenError = new SessionException(SessionException.Kind.PROTOCOL_FAILURE, "token request refused");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.onlyFailure().detail().contains("token request refused"), view.failures.toString());
        assertEquals(1, connection.closes);
        assertFalse(view.launched);
    }

    @Test
    void earlyChildDeathOffersRedownload() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        runner.failure = new LaunchFailure(LaunchFailure.Kind.EARLY_EXIT, "nxmc exited with code 1", 1, "NoClassDefFoundError", null);
        view.confirmRedownload = true;

        assertEquals(ConnectFlow.Outcome.RETRY_LOGIN, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("delete:5.2"));
        assertFalse(view.launched);
        assertEquals("", view.progress.get(view.progress.size() - 1), view.progress.toString());

        assertTrue(events.indexOf("unpin:5.2") < events.indexOf("delete:5.2"), events.toString());
    }

    @Test
    void anUndeletableBranchStopsInlineInsteadOfLoopingTheLogin() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        runner.failure = new LaunchFailure(LaunchFailure.Kind.EARLY_EXIT, "nxmc exited with code 1", 1, "NoClassDefFoundError", null);
        view.confirmRedownload = true;
        packages.deleteFailure = new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, "another " + "instance holds branch 5.2");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertEquals("Another launcher instance is working on this nxmc build.", failure.summary());
        assertTrue(failure.detail().contains("5.2"), failure.detail());
        assertTrue(view.diagnostics.isEmpty(), "a cache that would not give the branch up is an attempt state");
    }

    @Test
    void declinedRedownloadStopsAndShowsStderrTail() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        runner.failure = new LaunchFailure(LaunchFailure.Kind.EARLY_EXIT, "nxmc exited with code 1", 1, "NoClassDefFoundError", null);
        view.confirmRedownload = false;

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertFalse(events.contains("delete:5.2"));
        assertEquals(1, view.diagnostics.size(), view.diagnostics.toString());
        assertTrue(view.diagnostics.get(0).contains("NoClassDefFoundError"), view.diagnostics.get(0));
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertFalse(view.launched);
    }

    @Test
    void missingJavaRuntimeStopsWithoutRedownloadOffer() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        runner.failure = new LaunchFailure(LaunchFailure.Kind.JAVA_NOT_FOUND, "no Java runtime found");
        view.confirmRedownload = true;

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertFalse(events.contains("delete:5.2"));
        assertEquals(List.of("no Java runtime found"), view.diagnostics);
        assertTrue(view.failures.isEmpty(), "a dead child is a diagnostic, not an attempt-state message");
    }

    @Test
    void downloadProgressReachesTheView() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.downloaded = build("5.2", "5.2.3", "downloaded.jar");

        flow().connect(SERVER, "admin", "secret");
        assertEquals("5.2.3", view.lastDownloadVersion);
        assertEquals(50L, view.lastDownloaded);
        assertEquals(1024L, view.lastTotal);
    }

    @Test
    void updateDownloadProgressNamesTheNewBuildNotTheCachedOne() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        packages.downloaded = build("5.2", "5.2.3", "updated.jar");
        view.confirmUpdate = true;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals("5.2.3", view.lastDownloadVersion);
    }

    @Test
    void aDownloadFailureIsReportedInlineWithTheDetailBehindIt() {
        sessions.enqueue("c1");
        packages.downloadFailure = new PackageException(PackageException.Kind.DOWNLOAD_FAILED, "Download of " + "https://netxms.org/nxmc-5.2.3.jar failed: connection reset");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertEquals("The nxmc build could not be downloaded.", failure.summary());
        assertTrue(failure.detail().contains("nxmc-5.2.3.jar"), failure.detail());
        assertTrue(view.diagnostics.isEmpty(), "an attempt that failed is a status line, not a box");
    }

    @Test
    void everySummaryIsOneSentenceShortEnoughForTheStatusLine() {
        for (PackageException.Kind kind : PackageException.Kind.values()) {
            boolean neverReachesTheStatusLine = (kind == PackageException.Kind.CANCELLED) || (kind == PackageException.Kind.INVALID_PACKAGE);
            if (neverReachesTheStatusLine) {
                continue;
            }

            view = new FakeView();
            sessions = new FakeSessions(events);
            sessions.enqueue("c1");
            packages.downloadFailure = new PackageException(kind, UNFIT_DETAIL);
            assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
            Failure failure = view.onlyFailure();
            assertOneSentence(failure.summary());
            assertNotEquals(failure.detail(), failure.summary(), "kind " + kind + " has no summary of its own");
        }
    }

    @Test
    void manifestFailuresAreSummarisedByKind() {
        for (ManifestException.Kind kind : ManifestException.Kind.values()) {
            view = new FakeView();
            events = new ArrayList<>();
            sessions = new FakeSessions(events);
            packages = new FakePackages(home, events, clock);
            sessions.enqueue("c1");
            manifestFailure = new ManifestException(kind, UNFIT_DETAIL);

            assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
            Failure failure = view.onlyFailure();
            assertOneSentence(failure.summary());
            assertTrue(failure.summary().contains("manifest"), failure.summary());
            assertTrue(failure.detail().startsWith(UNFIT_DETAIL), failure.detail());
            assertNotEquals(failure.detail(), failure.summary(), "kind " + kind + " has no summary of its own");
        }
    }

    @Test
    void cancelAfterTheProbeStopsQuietly() throws Exception {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("c1:version")));
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "c1:close"
        ), events);
        assertEquals(1, connection.closes);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertEquals("Cancelled.", lastProgress());
        assertNoLoginAndNoLaunch();
        assertTrue(ServerRegistry.load(home).find(SERVER.address(), SERVER.port()).isPresent());
    }

    @Test
    void cancelAfterTheUpdateCheckStopsBeforeTheDownloadOffer() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.2", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        view.confirmUpdate = true;

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("manifest")));
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "manifest",
            "c1:close"
        ), events);
        assertEquals(1, connection.closes);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertEquals("Cancelled.", lastProgress());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void cancelAfterTheManifestFetchStopsBeforeTheFirstInstall() {
        FakeConnection connection = sessions.enqueue("c1");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("manifest")));
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "c1:close",
            "manifest"
        ), events);
        assertEquals(1, connection.closes);
        assertEquals(1, view.downloadQuestions);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertEquals("Cancelled.", lastProgress());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void cancelBeforeTheLoginPromptReleasesThePin() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("pin:5.2")));
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "manifest",
            "markChecked:5.2",
            "markUsed:5.2",
            "pin:5.2",
            "c1:close",
            "unpin:5.2"
        ), events);
        assertEquals(1, connection.closes);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertEquals("Cancelled.", lastProgress());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void cancelledDownloadStopsWithoutAnErrorBox() {
        FakeConnection connection = sessions.enqueue("c1");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("download:5" + ".2:5.2.3")));
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "c1:close",
            "manifest",
            "download:5.2:5.2.3"
        ), events);
        assertEquals(1, connection.closes);
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertEquals("Cancelled.", lastProgress());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void aDownloadThatFailedAsTheCancelArrivedIsStillAnError() {
        sessions.enqueue("c1");
        packages.downloadFailure = new PackageException(PackageException.Kind.DOWNLOAD_FAILED, "connection reset");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("download:5" + ".2:5.2.3")));
        assertEquals(List.of("connection reset"), view.details());
        assertNotEquals("Cancelled.", lastProgress(), "a failure the cancel did not cause must not be reported as " + "the" + " silent stop");
    }

    @Test
    void aCancelPressedAfterTheLastBoundaryStillHandsTheSessionOver() {
        FakeConnection connection = sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret", cancelAfter("c1:login" + ":admin")));
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "manifest",
            "markChecked:5.2",
            "markUsed:5.2",
            "pin:5.2",
            "c1:login:admin",
            "c1:token",
            "c1:close",
            "spawn:cached.jar:nx.example.com:4701:TOKEN-1",
            "unpin:5.2"
        ), events);
        assertEquals(1, connection.closes);
        assertTrue(view.launched);
        assertTrue(view.failures.isEmpty());
        assertNotEquals("Cancelled.", lastProgress());
    }

    /**
     * A token that trips once the flow has reached the point that logs the named event.
     */
    private CancelToken cancelAfter(String event) {
        return () -> events.contains(event);
    }

    private String lastProgress() {
        return view.progress.isEmpty() ? "" : view.progress.get(view.progress.size() - 1);
    }

    @Test
    void unwritableRegistryIsAWarningNotAFailure() throws Exception {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        Path blocked = home.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        registry = ServerRegistry.load(blocked);

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.warnings.size(), view.warnings.toString());
        assertTrue(view.warnings.get(0).contains(registry.file().toString()), view.warnings.get(0));
        assertTrue(view.failures.isEmpty());
        assertTrue(view.launched);
    }

    @Test
    void anUnwritableRegistryIsNotReportedAgainOnTheNextAttempt() throws Exception {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        Path blocked = home.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        registry = ServerRegistry.load(blocked);

        ConnectFlow flow = flow();
        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow.connect(SERVER, "admin", "secret"));
        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow.connect(SERVER, "admin", "secret"));
        assertEquals(1, view.warnings.size(), view.warnings.toString());
    }

    @Test
    void theSwitchOffKeepsTheManifestOutOfACacheHit() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        settings.setCheckForUpdates(false);

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(0, manifests.fetches, "the switch is off, so nothing may reach the manifest host");
        assertEquals(List.of(
            "open:nx.example.com:4701",
            "c1:version",
            "markUsed:5.2",
            "pin:5.2",
            "c1:login:admin",
            "c1:token",
            "c1:close",
            "spawn:cached.jar:nx.example.com:4701:TOKEN-1",
            "unpin:5.2"
        ), events);
    }

    @Test
    void anUnansweredSwitchKeepsTheManifestOutOfACacheHit() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        settings = LauncherSettings.load(home);
        assertTrue(settings.checkForUpdates().isEmpty());

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(0, manifests.fetches, "nothing reaches the network before somebody has said it may");
        assertTrue(view.launched);
    }

    @Test
    void theSwitchOffDoesNotGateAFirstInstall() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.downloaded = build("5.2", "5.2.3", "downloaded.jar");
        settings.setCheckForUpdates(false);

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.downloadQuestions, "the switch governs update checks; a first install has its own " + "consent question");
        assertEquals(1, manifests.fetches);
    }

    @Test
    void anUnansweredSwitchDoesNotGateAFirstInstallEither() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.downloaded = build("5.2", "5.2.3", "downloaded.jar");
        settings = LauncherSettings.load(home);
        assertTrue(settings.checkForUpdates().isEmpty());

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, view.downloadQuestions);
        assertEquals(1, manifests.fetches);
    }

    @Test
    void aFreshStampKeepsTheManifestOutOfACacheHitWithTheSwitchOn() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(checkedBuild("5.2", "5.2.3", "cached.jar", NOW.minus(Duration.ofHours(23))));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(0, manifests.fetches, "one lookup covers the branch for a day");
        assertTrue(view.launched);
    }

    @Test
    void aStampOlderThanTheBudgetChecksAgainAndAdvancesTheStamp() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(checkedBuild("5.2", "5.2.1", "cached.jar", NOW.minus(Duration.ofHours(25))));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        view.confirmUpdate = false;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, manifests.fetches);
        assertEquals(1, view.updateQuestions);
        assertEquals(Optional.of(NOW), packages.cached.orElseThrow().lastChecked(), "the stamp has to move, or the " + "next launch repeats it");
    }

    @Test
    void aStampExactlyOneBudgetOldIsDue() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(checkedBuild("5.2", "5.2.3", "cached.jar", NOW.minus(ConnectFlow.UPDATE_CHECK_INTERVAL)));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, manifests.fetches);
    }

    @Test
    void aStampOneMillisecondShortOfTheBudgetIsNotDue() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(checkedBuild("5.2", "5.2.3", "cached.jar", NOW.minus(ConnectFlow.UPDATE_CHECK_INTERVAL).plusMillis(1)));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(0, manifests.fetches);
    }

    @Test
    void anArmedBranchIsCheckedWithTheSwitchOff() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        settings.setCheckForUpdates(false);
        view.confirmUpdate = false;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, manifests.fetches);
        assertEquals(1, view.updateQuestions);
    }

    @Test
    void aBranchNoCheckEverRanForIsNotCheckedWithTheSwitchOff() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        settings.setCheckForUpdates(false);

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(0, manifests.fetches, "an upgrading user who declined the check must not be checked once per " + "branch anyway");
    }

    @Test
    void aFailedFetchStillSpendsTheBudget() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "manifest host is down");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("markCheckFailed:5.2"), events.toString());
        assertEquals(Optional.of(NOW), packages.cached.orElseThrow().lastChecked());
    }

    @Test
    void aFailedFetchLeavesAnArmedBranchArmed() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar"));
        settings.setCheckForUpdates(false);
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "manifest host is down");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertEquals(1, manifests.fetches);
        assertFalse(events.contains("markChecked:5.2"), events.toString());
        assertTrue(packages.cached.orElseThrow().updateArmed());
    }

    @Test
    void aCancelDuringTheFetchLeavesAnArmedBranchArmed() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar"));
        settings.setCheckForUpdates(false);
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret", cancelAfter("manifest")));
        assertEquals(1, manifests.fetches);
        assertFalse(events.contains("markChecked:5.2"), events.toString());
        assertFalse(events.contains("markCheckFailed:5.2"), events.toString());
        assertTrue(packages.cached.orElseThrow().updateArmed());
        assertEquals("Cancelled.", lastProgress());
        assertNoLoginAndNoLaunch();
    }

    @Test
    void anArmWrittenWhileAnAnsweredFetchWasOnTheWireSurvivesTheStampToo() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        manifests.duringFetch = () -> packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar"));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("markChecked:5.2"), events.toString());
        assertTrue(packages.cached.orElseThrow().updateArmed(), "an answered check disarms the request it found, not " + "one made by hand after it began");
    }

    @Test
    void anArmWrittenOverTheOneTheCheckFoundSurvivesTheStamp() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        CheckStamp foundInPlace = CheckStamp.NEVER.arm(0L);
        CheckStamp writtenDuringTheFetch = foundInPlace.arm(0L);
        packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar", foundInPlace));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        settings.setCheckForUpdates(false);
        view.confirmUpdate = false;
        manifests.duringFetch = () -> packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar", writtenDuringTheFetch));

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("markChecked:5.2"), events.toString());
        assertTrue(packages.cached.orElseThrow().updateArmed(), "the stamp served the arm it found; the one written " + "after it is a request nobody has answered");
    }

    @Test
    void anUpdateIsDecidedAgainstTheBuildTheFlowRead() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(build("5.2", "5.2.5", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.4", "https://netxms.org/nxmc-5.2.4.jar", SHA256, 1024));
        manifests.duringFetch = () -> packages.cached = Optional.of(build("5.2", "5.2.3", "downgraded.jar"));
        view.confirmUpdate = false;

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertEquals("5.2.5", packages.comparedVersion);
        assertEquals("5.2.5", view.offeredUpdateCached.full());
        assertEquals("5.2.4", view.offeredRelease.version(), "the release compared is the release offered");
        assertTrue(view.onlyFailure().summary().contains("removed or replaced"), view.failures.toString());
    }

    @Test
    void anAnsweredCheckDisarmsTheBranchItWasArmedFor() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        packages.cached = Optional.of(armedBuild("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        settings.setCheckForUpdates(false);
        view.confirmUpdate = false;

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("markChecked:5.2"), events.toString());
        assertFalse(packages.cached.orElseThrow().updateArmed());
    }

    @Test
    void anUntrustedManifestSpendsNothingBecauseNothingWasLearned() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        manifestFailure = new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "release URL is not HTTPS");

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        assertFalse(events.contains("markChecked:5.2"), events.toString());
        assertFalse(events.contains("markCheckFailed:5.2"), events.toString());
    }

    @Test
    void anUnstampedCheckDoesNotAbortALaunch() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.markCheckedFailure = new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, "another" + " instance holds branch 5.2");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertTrue(view.launched);
    }

    @Test
    void anUnstampedFailedCheckDoesNotAbortALaunchEither() {
        sessions.enqueue("c1");
        packages.cached = Optional.of(build("5.2", "5.2.3", "cached.jar"));
        packages.markCheckedFailure = new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, "another" + " instance holds branch 5.2");
        manifestFailure = new ManifestException(ManifestException.Kind.FETCH_FAILED, "manifest host is down");

        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow().connect(SERVER, "admin", "secret"));
        assertTrue(events.contains("markCheckFailed:5.2"), events.toString());
        assertTrue(view.failures.isEmpty(), view.failures.toString());
        assertTrue(view.launched);
    }

    @Test
    void aDeclinedUpdateIsNotOfferedAgainUntilTheBudgetExpires() {
        sessions.enqueue("c1");
        sessions.enqueue("c2");
        sessions.enqueue("c3");
        sessions.enqueue("c4");
        sessions.enqueue("c5");
        packages.cached = Optional.of(build("5.2", "5.2.1", "cached.jar"));
        packages.update = Optional.of(new ReleaseManifest.Release("5.2.3", "https://netxms.org/nxmc-5.2.3.jar", SHA256, 1024));
        view.confirmUpdate = false;

        ConnectFlow flow = flow();
        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow.connect(SERVER, "admin", "secret"));
        assertEquals(ConnectFlow.Outcome.LAUNCHED, flow.connect(SERVER, "admin", "secret"));
        assertEquals(1, view.updateQuestions, events.toString());
        assertEquals(1, manifests.fetches);

        Clock later = Clock.fixed(NOW.plus(ConnectFlow.UPDATE_CHECK_INTERVAL).plusSeconds(1), ZoneOffset.UTC);
        ConnectFlow tomorrow = new ConnectFlow(sessions, manifests, packages, runner, registry, view, settings, later);
        assertEquals(ConnectFlow.Outcome.LAUNCHED, tomorrow.connect(SERVER, "admin", "secret"));
        assertEquals(2, view.updateQuestions, "the budget has expired, so the offer comes back");
    }

    @Test
    void aBrokenEntryInTheFetchedManifestIsReportedAsItStands() throws Exception {
        sessions.enqueue("c1");
        manifest = ReleaseManifest.parse(MANIFEST_URL, manifestJson("5.2", "5.2.3", "https://mirror.example" + ".com" + "/nxmc-5.2.3.jar"));

        assertEquals(ConnectFlow.Outcome.STOPPED, flow().connect(SERVER, "admin", "secret"));
        Failure failure = view.onlyFailure();
        assertEquals("Release URL host does not match manifest host " + MANIFEST_URL.getHost() + ": https://mirror" + ".example.com/nxmc-5.2.3.jar", failure.detail());
        assertFalse(failure.detail().contains(ConnectFlow.IMPORT_HINT), failure.detail());
    }

    private void assertNoLoginAndNoLaunch() {
        assertFalse(events.stream().anyMatch(e -> e.contains(":login:")), "login must not be attempted: " + events);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("spawn:")), "nxmc must not be started: " + events);
        assertFalse(view.launched);
    }

    private PackageManager.CachedPackage build(String branch, String version, String jarName) {
        return build(branch, version, jarName, SHA256);
    }

    private PackageManager.CachedPackage build(String branch, String version, String jarName, String sha256) {
        return new PackageManager.CachedPackage(branch, home.resolve(jarName), ServerVersion.parse(version).orElseThrow(), sha256, Instant.EPOCH, 1024L, CheckStamp.NEVER);
    }

    private PackageManager.CachedPackage checkedBuild(String branch, String version, String jarName, Instant lastChecked) {
        return new PackageManager.CachedPackage(branch, home.resolve(jarName), ServerVersion.parse(version).orElseThrow(), SHA256, Instant.EPOCH, 1024L, CheckStamp.NEVER.check(lastChecked.toEpochMilli()));
    }

    private PackageManager.CachedPackage armedBuild(String branch, String version, String jarName) {
        return armedBuild(branch, version, jarName, CheckStamp.NEVER.arm(0L));
    }

    private PackageManager.CachedPackage armedBuild(String branch, String version, String jarName, CheckStamp arm) {
        return new PackageManager.CachedPackage(branch, home.resolve(jarName), ServerVersion.parse(version).orElseThrow(), SHA256, Instant.EPOCH, 1024L, arm);
    }

    private Path standaloneJar(String version) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, PackageManager.NXMC_MAIN_CLASS);
        attributes.putValue(PackageManager.PACKAGE_VERSION_ATTRIBUTE, version);

        Path file = Files.createDirectories(home.resolve("usb-stick")).resolve("nxmc-standalone.jar");
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("org/netxms/nxmc/BootstrapLoader.class"));
            jar.write("not really a class".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    @FunctionalInterface
    private interface TwoFactorExchange {
        void run(TwoFactorPrompt prompt) throws SessionException;
    }

    private static final class FakeConnection implements ServerConnection {
        final Deque<SessionException> changePasswordErrors = new ArrayDeque<>();
        private final List<String> events;
        private final String name;
        String version = "5.2.3";
        SessionException loginError;
        SessionException tokenError;
        TwoFactorExchange twoFactor;
        boolean passwordExpired;
        int graceLogins = 3;
        int closes;

        FakeConnection(List<String> events, String name) {
            this.events = events;
            this.name = name;
        }

        @Override
        public String serverVersion() {
            events.add(name + ":version");
            return version;
        }

        @Override
        public void login(String user, String password, TwoFactorPrompt prompt) throws SessionException {
            events.add(name + ":login:" + user);
            if (twoFactor != null) {
                twoFactor.run(prompt);
            }
            if (loginError != null) {
                throw loginError;
            }
        }

        @Override
        public boolean isPasswordExpired() {
            return passwordExpired;
        }

        @Override
        public int graceLogins() {
            return graceLogins;
        }

        @Override
        public void changePassword(String oldPassword, String newPassword) throws SessionException {
            events.add(name + ":changePassword:" + newPassword);
            SessionException failure = changePasswordErrors.poll();
            if (failure != null) {
                throw failure;
            }
            passwordExpired = false;
        }

        @Override
        public String requestToken() throws SessionException {
            events.add(name + ":token");
            if (tokenError != null) {
                throw tokenError;
            }
            return "TOKEN-1";
        }

        @Override
        public void close() {
            closes++;
            events.add(name + ":close");
        }
    }

    private static final class FakeSessions implements SessionService {
        private final List<String> events;
        private final Deque<Object> queue = new ArrayDeque<>();

        FakeSessions(List<String> events) {
            this.events = events;
        }

        FakeConnection enqueue(String name) {
            FakeConnection connection = new FakeConnection(events, name);
            queue.add(connection);
            return connection;
        }

        void enqueueFailure(SessionException failure) {
            queue.add(failure);
        }

        @Override
        public ServerConnection open(String host, int port) throws SessionException {
            events.add("open:" + host + ":" + port);
            Object next = queue.poll();
            if (next == null) {
                throw new IllegalStateException("unexpected connection attempt: " + events);
            }
            if (next instanceof SessionException failure) {
                throw failure;
            }

            return (FakeConnection) next;
        }
    }

    private static final class FakePackages extends PackageManager {
        private final List<String> events;
        private final Clock clock;
        Optional<CachedPackage> cached = Optional.empty();
        Optional<ReleaseManifest.Release> update = Optional.empty();
        CachedPackage downloaded;
        PackageException downloadFailure;
        PackageException markUsedFailure;
        PackageException markCheckedFailure;
        PackageException deleteFailure;
        PackageException pinFailure;
        boolean pinUnavailable;
        Optional<CachedPackage> cachedAfterPin;
        String comparedVersion;

        FakePackages(Path dataDir, List<String> events, Clock clock) {
            super(dataDir, clock, null);
            this.events = events;
            this.clock = clock;
        }

        @Override
        public IdentifiedBuild identify(Path source) {
            throw new AssertionError("the flow must never read a jar: " + source);
        }

        @Override
        public CachedPackage importBuild(IdentifiedBuild build) {
            throw new AssertionError("the flow must never import a jar: " + build);
        }

        @Override
        public Optional<CachedPackage> cached(String branch) {
            return cached;
        }

        @Override
        public Optional<ReleaseManifest.Release> updateAvailable(CachedPackage cached, ReleaseManifest manifest) {
            comparedVersion = cached.version().full();
            return update;
        }

        @Override
        public CachedPackage download(String branch, ReleaseManifest.Release release, ProgressListener progress, CancelToken cancel) throws PackageException {
            events.add("download:" + branch + ":" + release.version());
            if (downloadFailure != null) {
                throw downloadFailure;
            }
            if (cancel.cancelled()) {
                throw new PackageException(PackageException.Kind.CANCELLED, "Download of " + release.url() + " " + "cancelled");
            }

            progress.onProgress(50, release.size());
            cached = Optional.ofNullable(downloaded).map(this::installed);
            return cached.orElseThrow();
        }

        private CachedPackage installed(CachedPackage build) {
            CheckStamp stamp = cached.filter(CachedPackage::updateArmed).map(CachedPackage::checkStamp).orElseGet(() -> build.checkStamp().check(clock.millis()));
            return new CachedPackage(build.branch(), build.jar(), build.version(), build.sha256(), clock.instant(), build.size(), stamp);
        }

        @Override
        public void markUsed(String branch) throws PackageException {
            events.add("markUsed:" + branch);
            if (markUsedFailure != null) {
                throw markUsedFailure;
            }
        }

        @Override
        public boolean markChecked(String branch, CheckStamp servedArm) throws PackageException {
            events.add("markChecked:" + branch);
            if (markCheckedFailure != null) {
                throw markCheckedFailure;
            }

            if (cached.isEmpty()) {
                return false;
            }

            if (cached.map(build -> build.updateArmed() && !build.checkStamp().equals(servedArm)).orElse(Boolean.FALSE)) {
                return true;
            }

            cached = cached.map(build -> new CachedPackage(build.branch(), build.jar(), build.version(), build.sha256(), build.lastUsed(), build.size(), build.checkStamp().check(clock.millis())));
            return true;
        }

        @Override
        public void markCheckFailed(String branch) throws PackageException {
            events.add("markCheckFailed:" + branch);
            if (markCheckedFailure != null) {
                throw markCheckedFailure;
            }

            if (cached.map(CachedPackage::updateArmed).orElse(Boolean.FALSE)) {
                return;
            }

            cached = cached.map(build -> new CachedPackage(build.branch(), build.jar(), build.version(), build.sha256(), build.lastUsed(), build.size(), build.checkStamp().check(clock.millis())));
        }

        @Override
        Optional<Pin> pin(String branch) throws PackageException {
            events.add("pin:" + branch);
            if (cachedAfterPin != null) {
                cached = cachedAfterPin;
            }
            if (pinFailure != null) {
                throw pinFailure;
            }
            if (pinUnavailable) {
                return Optional.empty();
            }

            return Optional.of(() -> events.add("unpin:" + branch));
        }

        @Override
        public boolean delete(String branch) throws PackageException {
            events.add("delete:" + branch);
            if (deleteFailure != null) {
                throw deleteFailure;
            }

            cached = Optional.empty();
            return true;
        }
    }

    private static final class FakeRunner extends AppRunner {
        private final List<String> events;

        LaunchFailure failure;
        Path jar;

        FakeRunner(List<String> events) {
            super(Map.of(), Duration.ofMillis(1));
            this.events = events;
        }

        @Override
        public Process run(Path jar, String host, int port, String token) throws LaunchFailure {
            events.add("spawn:" + jar.getFileName() + ":" + host + ":" + port + ":" + token);
            this.jar = jar;
            if (failure != null) {
                throw failure;
            }
            return null;
        }
    }

    private record Failure(String summary, String detail) {
    }

    private static final class FakeView implements LauncherView {
        final List<String> progress = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Deque<String> newPasswords = new ArrayDeque<>();
        final List<Integer> promptedGraceLogins = new ArrayList<>();
        final List<String> promptedRejections = new ArrayList<>();

        boolean confirmDownload = true;
        boolean confirmUpdate;
        int updateQuestions;
        ReleaseManifest.Release offeredRelease;
        ServerVersion offeredUpdateCached;
        RuntimeException confirmUpdateFailure;
        RuntimeException promptNewPasswordFailure;
        boolean confirmRedownload;
        boolean launched;
        String lastDownloadVersion;
        long lastDownloaded;
        long lastTotal;
        int downloadQuestions;
        String askedBranch;
        ServerVersion askedVersion;
        String askedManifestHost;

        List<String> summaries() {
            return failures.stream().map(Failure::summary).toList();
        }

        List<String> details() {
            return failures.stream().map(Failure::detail).toList();
        }

        Failure onlyFailure() {
            assertEquals(1, failures.size(), failures.toString());
            return failures.get(0);
        }

        @Override
        public int selectMethod(List<String> methods) {
            return 0;
        }

        @Override
        public String enterCode(String challenge, String qrLabel) {
            return "123456";
        }

        @Override
        public void showProgress(String message) {
            progress.add(message);
        }

        @Override
        public void showDownloadProgress(String version, long downloaded, long total) {
            lastDownloadVersion = version;
            lastDownloaded = downloaded;
            lastTotal = total;
        }

        @Override
        public boolean confirmDownload(String branch, ServerVersion version, String manifestHost) {
            downloadQuestions++;
            askedBranch = branch;
            askedVersion = version;
            askedManifestHost = manifestHost;
            return confirmDownload;
        }

        @Override
        public boolean confirmUpdate(String branch, ServerVersion cached, ReleaseManifest.Release release) {
            if (confirmUpdateFailure != null) {
                throw confirmUpdateFailure;
            }
            updateQuestions++;
            offeredRelease = release;
            offeredUpdateCached = cached;
            return confirmUpdate;
        }

        @Override
        public String promptNewPassword(int graceLogins, String rejection) {
            if (promptNewPasswordFailure != null) {
                throw promptNewPasswordFailure;
            }
            promptedGraceLogins.add(graceLogins);
            promptedRejections.add(rejection);
            return newPasswords.poll();
        }

        @Override
        public boolean confirmRedownload(LaunchFailure failure) {
            return confirmRedownload;
        }

        @Override
        public void showFailure(String summary, String detail) {
            failures.add(new Failure(summary, detail));
        }

        @Override
        public void showWarning(String message) {
            warnings.add(message);
        }

        @Override
        public void showDiagnostic(String message) {
            diagnostics.add(message);
        }

        @Override
        public void launchSucceeded() {
            launched = true;
        }
    }

    private final class FakeManifests implements ConnectFlow.ManifestSource {
        int fetches;
        Runnable duringFetch;

        @Override
        public String host() {
            return manifestHost;
        }

        @Override
        public ReleaseManifest fetch() throws ManifestException {
            fetches++;
            events.add("manifest");
            if (duringFetch != null) {
                duringFetch.run();
            }
            if (manifestFailure != null) {
                throw manifestFailure;
            }
            return manifest;
        }
    }
}
