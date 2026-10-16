package org.netxms.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PackageManagerTest {
    @TempDir
    Path dataDir;

    private HttpServer server;
    private TestClock clock;
    private PackageManager manager;

    /**
     * Puts a non-empty directory where a file belongs, which every rename onto it is refused by, on every host.
     */
    private static Path blockAgainstEveryRename(Path path) throws IOException {
        Files.deleteIfExists(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("blocker"), "in the way");
        return path;
    }

    /**
     * What {@link PackageManager#download} leaves behind when it is killed after describing the build.
     */
    private static void writePendingMarker(Path branch, String version, String sha256) throws IOException {
        Files.writeString(branch.resolve(".pending-meta.json"),
                "{\"version\":\"" + version + "\",\"sha256\":\"" + sha256 + "\",\"lastUsed\":1}");
    }

    /**
     * Writes into an install's temporary file the way a {@link PackageManager.Filler} does.
     */
    private static void fill(Path temp, byte[] bytes) throws PackageException {
        try {
            Files.write(temp, bytes);
        } catch (IOException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR, "cannot write " + temp, e);
        }
    }

    private static boolean posixHost() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void respond(HttpExchange exchange, byte[] payload) throws IOException {
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static List<Path> temporaryFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(".part-") || name.endsWith(".tmp") || name.equals(".pending-meta.json");
            }).toList();
        }
    }

    private static ReleaseManifest manifest(String branch, String version) throws ManifestException {
        String json = """
                { "releases": { "%s": { "version": "%s", "url": "https://netxms.org/nxmc.jar", "sha256": "%s", "size": 1024 } } }
                """.formatted(branch, version, "a".repeat(64));
        return ReleaseManifest.parse(URI.create("https://netxms.org/nxmc-releases.json"), json);
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++)
            bytes[i] = (byte) (i % 251);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        clock = new TestClock();
        manager = newManager();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private PackageManager newManager() {
        return new PackageManager(dataDir, clock, HttpClient.newHttpClient());
    }

    /**
     * Which kind of filesystem the suite runs on is not something a test may depend on.
     */
    private PackageManager managerWithSharedLocks(boolean supported) {
        return new PackageManager(dataDir, clock, HttpClient.newHttpClient()) {
            @Override
            boolean sharedLocksSupported() {
                return supported;
            }
        };
    }

    @Test
    void cacheIsEmptyOnFirstRun() {
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(manager.cachedPackages().isEmpty());
        assertTrue(manager.branches().isEmpty());
    }

    @Test
    void rejectsBranchKeysThatAreNotVersions() {
        for (String branch : new String[]{
                null,
                "",
                "5",
                "../etc",
                "5.2/x",
                "latest"
        })
            assertThrows(IllegalArgumentException.class, () -> manager.branchDir(branch), branch);
    }

    @Test
    void downloadStoresVerifiedJarAndReportsProgress() throws Exception {
        byte[] payload = payload(300_000);
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload);

        List<Long> progress = new ArrayList<>();
        PackageManager.CachedPackage cached = manager.download("5.2", release, (done, total) -> {
            assertEquals(payload.length, total);
            progress.add(done);
        });

        assertEquals("5.2", cached.branch());
        assertEquals("5.2.3", cached.version().full());
        assertEquals(payload.length, cached.size());
        assertEquals(sha256(payload), cached.sha256());
        assertEquals(clock.instant().toEpochMilli(), cached.lastUsed().toEpochMilli());
        assertArrayEquals(payload, Files.readAllBytes(cached.jar()));
        assertEquals(dataDir.resolve("versions").resolve("5.2").resolve("nxmc-standalone.jar"), cached.jar());

        assertFalse(progress.isEmpty());
        assertEquals(payload.length, progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++)
            assertTrue(progress.get(i) > progress.get(i - 1), "progress must be monotonic");

        assertEquals(Optional.of("5.2.3"), manager.cached("5.2").map(c -> c.version().full()));
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void downloadCarriesTheSameUserAgentAsTheManifestFetch() throws Exception {
        byte[] payload = payload(1024);
        Map<String, List<String>> sent = new HashMap<>();
        server.createContext("/5.2/nxmc.jar", exchange -> {
            sent.putAll(exchange.getRequestHeaders());
            respond(exchange, payload);
        });

        manager.download("5.2", new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/nxmc.jar", sha256(payload),
                payload.length), null);

        assertEquals(List.of(ManifestClient.USER_AGENT), sent.get("User-agent"));
        assertFalse(sent.toString().contains(System.getProperty("java.version")), sent.toString());
    }

    @Test
    void downloadWithoutProgressListenerIsAllowed() throws Exception {
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload(1024));
        assertEquals("5.2.3", manager.download("5.2", release, null).version().full());
    }

    @Test
    void checksumMismatchDeletesTemporaryFileAndKeepsCacheEmpty() throws Exception {
        byte[] payload = payload(4096);
        ReleaseManifest.Release honest = publish("/5.2/nxmc.jar", "5.2.3", payload);
        ReleaseManifest.Release tampered = new ReleaseManifest.Release(honest.version(), honest.url(), "f".repeat(64)
                , honest.size());

        PackageException e = assertThrows(PackageException.class, () -> manager.download("5.2", tampered, null));
        assertEquals(PackageException.Kind.HASH_MISMATCH, e.kind());
        assertTrue(manager.cached("5.2").isEmpty());
        assertFalse(Files.exists(dataDir.resolve("versions").resolve("5.2").resolve("nxmc-standalone.jar")));
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void downloadReturnsItsOwnBuildEvenWhenAnotherInstanceReplacesTheBranchRightAfter() throws Exception {
        byte[] mine = payload(2048);
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", mine);
        ReleaseManifest.Release newer = publish("/5.2/nxmc-4.jar", "5.2.4", payload(3072));

        PackageManager overtaken = new PackageManager(dataDir, clock, HttpClient.newHttpClient()) {
            @Override
            void evictLeastRecentlyUsed(String keep) {
                try {
                    newManager().download("5.2", newer, null);
                } catch (PackageException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        PackageManager.CachedPackage cached = overtaken.download("5.2", release, null);

        assertEquals("5.2.3", cached.version().full());
        assertEquals(sha256(mine), cached.sha256());
        assertEquals("5.2.4", manager.cached("5.2").orElseThrow().version().full());
    }

    @Test
    void checksumMismatchLeavesPreviouslyCachedBuildIntact() throws Exception {
        byte[] good = payload(2048);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", good), null);

        ReleaseManifest.Release bad = publish("/5.2/nxmc-4.jar", "5.2.4", payload(3072));
        ReleaseManifest.Release tampered = new ReleaseManifest.Release(bad.version(), bad.url(), "0".repeat(64),
                bad.size());

        assertEquals(PackageException.Kind.HASH_MISMATCH, assertThrows(PackageException.class,
                () -> manager.download("5.2", tampered, null)).kind());

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full());
        assertArrayEquals(good, Files.readAllBytes(cached.jar()));
    }

    @Test
    void reportsDownloadFailureOnHttpError() throws Exception {
        byte[] payload = payload(1024);
        server.createContext("/missing.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/missing.jar",
                sha256(payload), payload.length);

        PackageException e = assertThrows(PackageException.class, () -> manager.download("5.2", release, null));
        assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void refusesABuildRedirectedOffThePublishedHost() throws Exception {
        byte[] payload = payload(1024);
        server.createContext("/moved.jar", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://localhost:" + server.getAddress().getPort() + "/5" + ".2/nxmc.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        publish("/5.2/nxmc.jar", "5.2.3", payload);
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/moved.jar",
                sha256(payload), payload.length);

        PackageManager redirecting = new PackageManager(dataDir, clock,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());

        PackageException e = assertThrows(PackageException.class, () -> redirecting.download("5.2", release, null));
        assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
        assertTrue(e.getMessage().contains("redirected to"), e.getMessage());
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void refusesABuildRedirectedToAnotherPortOnThePublishedHost() throws Exception {
        byte[] payload = payload(1024);
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        elsewhere.createContext("/5.2/nxmc.jar", exchange -> respond(exchange, payload));
        elsewhere.start();
        try {
            server.createContext("/moved.jar", exchange -> {
                exchange.getResponseHeaders().add("Location", "http://" + elsewhere.getAddress().getHostString() +
                        ":" + elsewhere.getAddress().getPort() + "/5.2/nxmc.jar");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/moved.jar",
                    sha256(payload), payload.length);

            PackageManager redirecting = new PackageManager(dataDir, clock,
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());

            PackageException e = assertThrows(PackageException.class, () -> redirecting.download("5.2", release, null));
            assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
            assertTrue(e.getMessage().contains("redirected to"), e.getMessage());
            assertTrue(manager.cached("5.2").isEmpty());
            assertTrue(temporaryFiles("5.2").isEmpty());
        } finally {
            elsewhere.stop(0);
        }
    }

    @Test
    void followsARedirectThatStaysOnThePublishedHost() throws Exception {
        byte[] payload = payload(1024);
        server.createContext("/moved.jar", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl() + "/5.2/nxmc.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        publish("/5.2/nxmc.jar", "5.2.3", payload);
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/moved.jar",
                sha256(payload), payload.length);

        PackageManager redirecting = new PackageManager(dataDir, clock,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());

        assertArrayEquals(payload, Files.readAllBytes(redirecting.download("5.2", release, null).jar()));
    }

    @Test
    void reportsDownloadFailureWhenServerIsUnreachable() throws Exception {
        byte[] payload = payload(1024);
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/nxmc.jar",
                sha256(payload), payload.length);
        server.stop(0);
        server = null;

        assertEquals(PackageException.Kind.DOWNLOAD_FAILED, assertThrows(PackageException.class,
                () -> manager.download("5.2", release, null)).kind());
    }

    @Test
    void cleanupRemovesStaleTemporaryFilesButKeepsCachedBuild() throws Exception {
        byte[] payload = payload(1024);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", payload), null);

        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.writeString(branch.resolve(".part-abandoned"), "half a jar");
        Files.writeString(branch.resolve(".part-another"), "also junk");
        assertEquals(2, temporaryFiles("5.2").size());

        manager.cleanupStaleTemporaryFiles();

        assertTrue(temporaryFiles("5.2").isEmpty());
        assertArrayEquals(payload, Files.readAllBytes(manager.cached("5.2").orElseThrow().jar()));
    }

    @Test
    void cleanupRemovesInterruptedMetadataWrites() throws Exception {
        install("5.2", "5.2.3");

        Path branch = dataDir.resolve("versions").resolve("5.2");
        Path leftover = Files.writeString(branch.resolve("meta.json1234567890.tmp"), "{ half written");

        manager.cleanupStaleTemporaryFiles();

        assertFalse(Files.exists(leftover));
        assertEquals("5.2.3", manager.cached("5.2").orElseThrow().version().full(), "the real metadata must survive");
    }

    @Test
    void refusesABodyLargerThanThePublishedSize() throws Exception {
        byte[] payload = payload(8192);
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload);
        ReleaseManifest.Release understated = new ReleaseManifest.Release(release.version(), release.url(),
                release.sha256(), 1024);

        PackageException e = assertThrows(PackageException.class, () -> manager.download("5.2", understated, null));
        assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
        assertTrue(e.getMessage().contains("1024"), e.getMessage());
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void unwritableMetadataPutsThePreviouslyCachedJarBack() throws Exception {
        byte[] kept = payload(1024);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", kept), null);

        Path branch = dataDir.resolve("versions").resolve("5.2");
        Path meta = blockAgainstEveryRename(branch.resolve("meta.json"));

        byte[] downloaded = payload(2048);
        ReleaseManifest.Release release = publish("/5.2/nxmc-4.jar", "5.2.4", downloaded);
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> manager.download(
                "5.2", release, null)).kind());

        Path jar = branch.resolve("nxmc-standalone.jar");
        assertArrayEquals(kept, Files.readAllBytes(jar),
                "a jar its metadata does not describe must not be left in " + "place");
        assertTrue(manager.cached("5.2").isEmpty(),
                "the metadata this test broke is unreadable, so the branch is a " + "miss");
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void unwritableMetadataDropsTheJarWhenThereIsNoPreviousBuildToPutBack() throws Exception {
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.createDirectories(branch);
        Path meta = blockAgainstEveryRename(branch.resolve("meta.json"));

        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload(2048));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> manager.download(
                "5.2", release, null)).kind());

        assertFalse(Files.exists(branch.resolve("nxmc-standalone.jar")), "a jar without its own metadata must not be "
                + "kept");
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void failedDownloadFinishesAnInstallAnotherInstanceWasKilledDuring() throws Exception {
        install("5.2", "5.2.3");

        byte[] installed = payload(2048);
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.write(branch.resolve("nxmc-standalone.jar"), installed);
        writePendingMarker(branch, "5.2.4", sha256(installed));

        ReleaseManifest.Release honest = publish("/5.2/nxmc-5.jar", "5.2.5", payload(3072));
        ReleaseManifest.Release tampered = new ReleaseManifest.Release(honest.version(), honest.url(), "0".repeat(64)
                , honest.size());
        assertEquals(PackageException.Kind.HASH_MISMATCH, assertThrows(PackageException.class,
                () -> manager.download("5.2", tampered, null)).kind());

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.4", cached.version().full());
        assertEquals(sha256(installed), cached.sha256());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void startupFinishesAnInstallKilledBetweenTheJarAndItsMetadata() throws Exception {
        install("5.2", "5.2.3");

        byte[] installed = payload(2048);
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.write(branch.resolve("nxmc-standalone.jar"), installed);
        writePendingMarker(branch, "5.2.4", sha256(installed));

        manager.cleanupStaleTemporaryFiles();

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.4", cached.version().full(), "the jar on disk is the new build and must be reported as one");
        assertEquals(sha256(installed), cached.sha256());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void startupLeavesTheJarAloneWhenTheMarkerDoesNotDescribeIt() throws Exception {
        install("5.2", "5.2.3");
        Path branch = dataDir.resolve("versions").resolve("5.2");
        byte[] cachedJar = Files.readAllBytes(branch.resolve("nxmc-standalone.jar"));

        writePendingMarker(branch, "5.2.4", sha256(payload(4096)));

        manager.cleanupStaleTemporaryFiles();

        assertEquals("5.2.3", manager.cached("5.2").orElseThrow().version().full());
        assertArrayEquals(cachedJar, Files.readAllBytes(branch.resolve("nxmc-standalone.jar")));
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void startupDiscardsAMarkerItCannotRead() throws Exception {
        install("5.2", "5.2.3");

        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.writeString(branch.resolve(".pending-meta.json"), "{\"version\":\"5.2.4\"");

        manager.cleanupStaleTemporaryFiles();

        assertEquals("5.2.3", manager.cached("5.2").orElseThrow().version().full());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    /**
     * On Windows the jar nxmc holds open is the entry a sweep cannot remove, and a name-ordered
     * directory puts it after {@code meta.json}. Pinned through the entry removal, since no host
     * guarantees the order a directory is read in.
     */
    @Test
    void aSweepStopsOnTheJarBeforeItCanOrphanTheDescription() throws Exception {
        install("5.2", "5.2.3");
        Files.writeString(dataDir.resolve("versions").resolve("5.2").resolve(".part-4711"), "left over");

        List<String> attempted = new ArrayList<>();
        PackageManager holding = new PackageManager(dataDir, clock, HttpClient.newHttpClient()) {
            @Override
            void deleteEntry(DirectoryStream<Path> stream, Path dir, Path name) throws IOException {
                attempted.add(name.toString());
                if (PackageManager.JAR_NAME.equals(name.toString())) {
                    throw new AccessDeniedException(name.toString()); // what Windows answers for an open file
                }
                super.deleteEntry(stream, dir, name);
            }
        };

        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class,
                () -> holding.delete("5" + ".2")).kind());

        assertEquals(List.of(PackageManager.JAR_NAME), attempted, "nothing else may be attempted once the jar refuses");
        assertEquals("5.2.3", manager.cached("5.2").orElseThrow().version().full(),
                "the branch still reads back as " + "the build it held");
    }

    /**
     * A delete the filesystem refuses is a cache error, and the branch it could not clear stays whole.
     */
    @Test
    void aBranchThatCannotBeClearedIsReportedAndLeftReadable() throws Exception {
        assumeTrue(posixHost(), "needs POSIX permissions to refuse a delete");

        install("5.2", "5.2.3");
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(branch);
        Files.setPosixFilePermissions(branch, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(branch), "the test user can write regardless of permissions");

            assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class,
                    () -> manager.delete("5.2")).kind());
        } finally {
            Files.setPosixFilePermissions(branch, original);
        }

        assertEquals("5.2.3", manager.cached("5.2").orElseThrow().version().full(), "a branch a delete could not " +
                "clear must still read back as the build it held");
    }

    /**
     * Eviction is housekeeping: a branch it cannot remove stays whole and the next candidate goes.
     */
    @Test
    void evictionLeavesABranchItCannotRemoveIntact() throws Exception {
        assumeTrue(posixHost(), "needs POSIX permissions to refuse a delete");

        install("5.0", "5.0.7");
        clock.advance(Duration.ofMinutes(1));
        install("5.1", "5.1.2");
        clock.advance(Duration.ofMinutes(1));
        install("5.2", "5.2.3");

        Path oldest = dataDir.resolve("versions").resolve("5.0");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(oldest);
        Files.setPosixFilePermissions(oldest, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(oldest), "the test user can write regardless of permissions");

            clock.advance(Duration.ofMinutes(1));
            install("5.3", "5.3.1");
        } finally {
            Files.setPosixFilePermissions(oldest, original);
        }

        assertEquals("5.0.7", manager.cached("5.0").orElseThrow().version().full(),
                "the branch eviction could not " + "remove stays whole");
        assertTrue(manager.cached("5.1").isEmpty(), "the slot passes to the next oldest candidate");
    }

    @Test
    void clearCacheRemovesEveryBranchItCanEvenWhenOneIsBusy() throws Exception {
        install("5.0", "5.0.7");
        install("5.1", "5.1.2");
        install("5.2", "5.2.3");

        PackageManager other = newManager();
        try (PackageManager.BranchLock held = manager.lockBranch("5.1")) {
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    other::clearCache).kind());
        }

        assertEquals(List.of("5.1"), other.branches());
    }

    @Test
    void aJarThatCannotBeReplacedLeavesTheBranchAsItWas() throws Exception {
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.createDirectories(branch);
        Path jar = blockAgainstEveryRename(branch.resolve("nxmc-standalone.jar"));

        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload(2048));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> manager.download(
                "5.2", release, null)).kind());

        assertTrue(Files.isDirectory(jar), "a failed install must leave the branch exactly as it found it");
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void failedMetadataWriteRestoresThePreviouslyCachedBuild() throws Exception {
        byte[] kept = payload(1024);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", kept), null);

        PackageManager failing = new PackageManager(dataDir, clock, HttpClient.newHttpClient()) {
            @Override
            void writeMeta(Path dir, PackageManager.Meta meta) throws PackageException {
                throw new PackageException(PackageException.Kind.CACHE_ERROR, "metadata cannot be written");
            }
        };

        ReleaseManifest.Release release = publish("/5.2/nxmc-4.jar", "5.2.4", payload(2048));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> failing.download(
                "5.2", release, null)).kind());

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full(), "a failed install must leave the branch as it found it");
        assertEquals(sha256(kept), cached.sha256());
        assertArrayEquals(kept, Files.readAllBytes(cached.jar()));
        assertTrue(temporaryFiles("5.2").isEmpty(), "the set-aside copy must not survive as a leftover");
    }

    /**
     * The only case where {@code setAside} has a build to put back, which is what {@code restore} exists for.
     */
    @Test
    void aFailedMoveOverAnInstalledBuildPutsThePreviousJarBack() throws Exception {
        byte[] kept = payload(1024);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", kept), null);

        AtomicBoolean refused = new AtomicBoolean();
        PackageManager failing = new PackageManager(dataDir, clock, HttpClient.newHttpClient()) {
            @Override
            void move(Path source, Path target) throws IOException {
                if (PackageManager.JAR_NAME.equals(target.getFileName().toString()) && refused.compareAndSet(false,
                        true)) {
                    Files.deleteIfExists(target);
                    throw new IOException("the target was removed before the move failed");
                }
                super.move(source, target);
            }
        };

        ReleaseManifest.Release release = publish("/5.2/nxmc-4.jar", "5.2.4", payload(2048));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> failing.download(
                "5.2", release, null)).kind());

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full(), "a refused rename must leave the branch as it found it");
        assertEquals(sha256(kept), cached.sha256());
        assertArrayEquals(kept, Files.readAllBytes(cached.jar()));
    }

    /**
     * {@code cached} only checks that a jar is there, so a jar the rollback could not replace has to read as a miss.
     */
    @Test
    void aRollbackThatIsRefusedTooLeavesTheBranchACacheMiss() throws Exception {
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", payload(1024)), null);

        AtomicBoolean replaced = new AtomicBoolean();
        PackageManager failing = new PackageManager(dataDir, clock, HttpClient.newHttpClient()) {
            @Override
            void move(Path source, Path target) throws IOException {
                if (!PackageManager.JAR_NAME.equals(target.getFileName().toString())) {
                    super.move(source, target);
                    return;
                }
                if (replaced.compareAndSet(false, true)) {
                    Files.write(target, new byte[100]);
                }
                throw new IOException("refused");
            }
        };

        ReleaseManifest.Release release = publish("/5.2/nxmc-4.jar", "5.2.4", payload(2048));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> failing.download(
                "5.2", release, null)).kind());

        assertTrue(manager.cached("5.2").isEmpty(), "a jar meta.json does not describe must not read back as a build");
        assertFalse(Files.exists(dataDir.resolve("versions").resolve("5.2").resolve(PackageManager.JAR_NAME)));
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    @Test
    void aFillerThatFailsPartWayLeavesAFreshBranchWithNothingInIt() throws Exception {
        PackageException e = assertThrows(PackageException.class, () -> manager.install("5.2", temp -> {
            fill(temp, payload(4096));
            throw new PackageException(PackageException.Kind.DOWNLOAD_FAILED, "gave up halfway");
        }));

        assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
        assertFalse(Files.exists(dataDir.resolve("versions").resolve("5.2")),
                "a branch nothing was installed into " + "must be given back");
    }

    @Test
    void aFillerThatFailsOverACachedBuildPutsThatBuildBack() throws Exception {
        byte[] kept = payload(1024);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", kept), null);

        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> manager.install("5"
                + ".2", temp -> {
            fill(temp, payload(2048));
            throw new PackageException(PackageException.Kind.CACHE_ERROR, "the source went away");
        })).kind());

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full());
        assertEquals(sha256(kept), cached.sha256());
        assertArrayEquals(kept, Files.readAllBytes(cached.jar()));
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    @Test
    void aFillerThatSucceedsInstallsWhateverItWrote() throws Exception {
        byte[] bytes = payload(3072);
        String hash = sha256(bytes);
        PackageManager.CachedPackage installed = manager.install("5.2", temp -> {
            fill(temp, bytes);
            return new PackageManager.Identity("5.2.7", hash);
        });

        assertEquals("5.2", installed.branch());
        assertEquals("5.2.7", installed.version().full());
        assertEquals(sha256(bytes), installed.sha256());
        assertEquals(bytes.length, installed.size());
        assertEquals(clock.instant().toEpochMilli(), installed.lastUsed().toEpochMilli());
        assertArrayEquals(bytes, Files.readAllBytes(installed.jar()));

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.7", cached.version().full());
        assertEquals(sha256(bytes), cached.sha256());
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    @Test
    void settingTheCachedBuildAsideKeepsItUnderItsOwnName() throws Exception {
        byte[] installed = payload(1024);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", installed), null);
        Path jar = manager.cached("5.2").orElseThrow().jar();

        Path backup = PackageManager.setAside(jar.getParent(), jar);

        assertNotNull(backup);
        assertTrue(Files.isRegularFile(jar), "the cached build must stay launchable while the new one is installed");
        assertArrayEquals(installed, Files.readAllBytes(jar));
        assertArrayEquals(installed, Files.readAllBytes(backup));

        Files.delete(backup);
    }

    @Test
    void downloadCleansUpTemporaryFilesLeftByAnInterruptedRun() throws Exception {
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.createDirectories(branch);
        Files.writeString(branch.resolve(".part-interrupted"), "half a jar");

        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", payload(2048)), null);
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void evictsLeastRecentlyUsedBranchAboveTheLimit() throws Exception {
        install("5.0", "5.0.7");
        clock.advance(Duration.ofMinutes(10));
        install("5.1", "5.1.2");
        clock.advance(Duration.ofMinutes(10));
        install("5.2", "5.2.3");
        assertEquals(List.of("5.0", "5.1", "5.2"), manager.branches());

        clock.advance(Duration.ofMinutes(10));
        manager.markUsed("5.0"); // oldest download, but freshly used - 5.1 is now the eviction victim

        clock.advance(Duration.ofMinutes(10));
        install("6.0", "6.0.1");

        assertEquals(List.of("5.0", "5.2", "6.0"), manager.branches());
        assertTrue(manager.cached("5.1").isEmpty());
        assertFalse(Files.exists(dataDir.resolve("versions").resolve("5.1")));
        assertEquals(PackageManager.MAX_CACHED_BRANCHES, manager.cachedPackages().size());
        assertEquals(List.of("6.0", "5.0", "5.2"),
                manager.cachedPackages().stream().map(PackageManager.CachedPackage::branch).toList());
    }

    @Test
    void keepsExactlyThreeBranchesWithoutEviction() throws Exception {
        install("5.0", "5.0.7");
        clock.advance(Duration.ofMinutes(1));
        install("5.1", "5.1.2");
        clock.advance(Duration.ofMinutes(1));
        install("5.2", "5.2.3");

        assertEquals(3, manager.cachedPackages().size());
        assertEquals(List.of("5.0", "5.1", "5.2"), manager.branches());
    }

    @Test
    void updateIsOfferedOnlyForNewerPatchOfCachedBranch() throws Exception {
        install("5.2", "5.2.3");
        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();

        assertEquals(Optional.of("5.2.4"),
                manager.updateAvailable(cached, manifest("5.2", "5.2.4")).map(ReleaseManifest.Release::version));
        assertTrue(manager.updateAvailable(cached, manifest("5.2", "5.2.3")).isEmpty());
        assertTrue(manager.updateAvailable(cached, manifest("5.2", "5.2.2")).isEmpty());
        assertTrue(manager.updateAvailable(cached, manifest("6.0", "6.0.1")).isEmpty());
    }

    @Test
    void anUpdateCheckAnswersAboutTheBuildItWasHandedRatherThanRereadingTheBranch() throws Exception {
        install("5.2", "5.2.3");
        PackageManager.CachedPackage read = manager.cached("5.2").orElseThrow();

        install("5.2", "5.2.5");

        assertTrue(manager.updateAvailable(manager.cached("5.2").orElseThrow(), manifest("5.2", "5.2.4")).isEmpty(),
                "the branch itself is past 5.2.4");
        assertEquals(Optional.of("5.2.4"),
                manager.updateAvailable(read, manifest("5.2", "5.2.4")).map(ReleaseManifest.Release::version),
                "the " + "answer is about the reading it was handed, not about whatever the branch holds now");
    }

    @Test
    void secondInstanceReportsBranchBusy() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = newManager();
        try (PackageManager.BranchLock held = manager.lockBranch("5.2")) {
            ReleaseManifest.Release release = publish("/5.2/nxmc-4.jar", "5.2.4", payload(1024));
            PackageException e = assertThrows(PackageException.class, () -> other.download("5.2", release, null));
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, e.kind());
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.delete("5.2")).kind());
        }

        assertEquals("5.2.4",
                other.download("5.2", publish("/5.2/again.jar", "5.2.4", payload(2048)), null).version().full());
    }

    @Test
    void pinnedBranchSurvivesEvictionByAnotherInstance() throws Exception {
        install("5.0", "5.0.7"); // least recently used, so the one eviction would pick
        clock.advance(Duration.ofMinutes(10));
        install("5.1", "5.1.2");
        clock.advance(Duration.ofMinutes(10));
        install("5.2", "5.2.3");

        PackageManager other = newManager();
        clock.advance(Duration.ofMinutes(10));
        try (PackageManager.Pin pin = manager.pin("5.0").orElseThrow()) {
            other.download("6.0", publish("/6.0/nxmc.jar", "6.0.1", payload(1024)), null);
        }

        assertEquals(List.of("5.0", "5.2", "6.0"), manager.branches());
        assertEquals("5.0.7", manager.cached("5.0").orElseThrow().version().full());
        assertEquals(PackageManager.MAX_CACHED_BRANCHES, manager.cachedPackages().size());
    }

    @Test
    void pinnedBranchIsBusyForWritersUntilTheLaunchReleasesIt() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = newManager();
        ReleaseManifest.Release release = publish("/5.2/nxmc-4.jar", "5.2.4", payload(1024));
        try (PackageManager.Pin pin = manager.pin("5.2").orElseThrow()) {
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.delete("5.2")).kind());
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.download("5.2", release, null)).kind());
        }

        assertTrue(other.delete("5.2"));
    }

    @Test
    void releasedPinCanBeReleasedAgain() throws Exception {
        install("5.2", "5.2.3");

        PackageManager.Pin pin = manager.pin("5.2").orElseThrow();
        pin.close();
        pin.close();

        assertTrue(manager.delete("5.2"));
    }

    @Test
    void branchBeingWrittenByAnotherInstanceCannotBePinned() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = managerWithSharedLocks(true);
        try (PackageManager.BranchLock held = manager.lockBranch("5.2")) {
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.pin("5.2", 2, 1)).kind());
        }

        try (PackageManager.Pin pin = other.pin("5.2", 2, 1).orElseThrow()) {
            assertNotNull(pin);
        }
    }

    @Test
    void defaultRetryBudgetOutlastsAStartupRecoveryHash() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = managerWithSharedLocks(true);
        PackageManager.BranchLock held = manager.lockBranch("5.2");
        long recoveryHashMillis = 900L;
        Thread hashing = new Thread(() -> {
            try {
                Thread.sleep(recoveryHashMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            held.close();
        });
        hashing.start();
        try {
            try (PackageManager.Pin pin = other.pin("5.2").orElseThrow()) {
                assertNotNull(pin);
            }
        } finally {
            hashing.join();
            held.close();
        }
    }

    @Test
    void shortContentionIsWaitedOutRatherThanReportedBusy() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = managerWithSharedLocks(true);
        PackageManager.BranchLock held = manager.lockBranch("5.2");
        Thread stamping = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            held.close();
        });
        stamping.start();
        try {
            try (PackageManager.Pin pin = other.pin("5.2", 100, 20).orElseThrow()) {
                assertNotNull(pin);
            }
        } finally {
            stamping.join();
            held.close();
        }
    }

    @Test
    void branchIsSimplyUnpinnableWhereThePlatformHasNoSharedLocks() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = managerWithSharedLocks(false);
        try (PackageManager.BranchLock held = manager.lockBranch("5.2")) {
            assertTrue(other.pin("5.2", 2, 1).isEmpty());
        }
    }

    @Test
    void sharedLockSupportIsProbedOnItsOwnLockFile() throws Exception {
        manager.sharedLocksSupported();

        Path probe = dataDir.resolve("versions").resolve(".locks").resolve(".shared-locks.lock");
        assertTrue(Files.isRegularFile(probe));
        try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, false)) {
            assertNotNull(lock, "the probe must release the lock it took");
        }
    }

    @Test
    void concurrentProbesDoNotAnswerForEachOther() throws Exception {
        boolean expected = newManager().sharedLocksSupported();

        int probes = 8;
        CyclicBarrier start = new CyclicBarrier(probes);
        List<Thread> threads = new ArrayList<>();
        boolean[] answers = new boolean[probes];
        for (int i = 0; i < probes; i++) {
            int index = i;
            PackageManager probe = newManager();
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                answers[index] = probe.sharedLocksSupported();
            });
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads)
            thread.join();

        for (int i = 0; i < probes; i++)
            assertEquals(expected, answers[i], "probe " + i);
    }

    @Test
    void branchLockOutlivesTheBranchDirectoryItGuards() throws Exception {
        install("5.1", "5.1.2");
        Path lockFile = dataDir.resolve("versions").resolve(".locks").resolve("5.1.lock");
        assertTrue(Files.isRegularFile(lockFile));

        assertTrue(manager.delete("5.1"));

        assertTrue(Files.isRegularFile(lockFile), "the lock file must survive the branch it guards");
        assertFalse(Files.exists(dataDir.resolve("versions").resolve("5.1")));
        assertTrue(manager.branches().isEmpty(), "the lock directory is not a branch");
        assertTrue(manager.cachedPackages().isEmpty());
    }

    @Test
    void deleteRemovesOneBranchAndLeavesTheOthers() throws Exception {
        install("5.1", "5.1.2");
        install("5.2", "5.2.3");

        assertTrue(manager.delete("5.1"));
        assertFalse(Files.exists(dataDir.resolve("versions").resolve("5.1")));
        assertTrue(manager.cached("5.1").isEmpty());
        assertEquals("5.2.3", manager.cached("5.2").orElseThrow().version().full());

        assertFalse(manager.delete("5.1"), "deleting an absent branch is a no-op");
    }

    @Test
    void deleteRemovesJarAndMetadataAndLeavesNeighboursIntact() throws Exception {
        byte[] kept = payload(2048);
        manager.download("5.1", publish("/5.1/nxmc.jar", "5.1.2", payload(1024)), null);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", kept), null);
        install("6.0", "6.0.1");

        Path removed = dataDir.resolve("versions").resolve("5.1");
        Files.writeString(removed.resolve(".part-leftover"), "junk");

        assertTrue(manager.delete("5.1"));

        assertFalse(Files.exists(removed.resolve("nxmc-standalone.jar")));
        assertFalse(Files.exists(removed.resolve("meta.json")));
        assertFalse(Files.exists(removed));
        assertEquals(List.of("5.2", "6.0"), manager.branches());

        PackageManager.CachedPackage neighbour = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", neighbour.version().full());
        assertEquals(sha256(kept), neighbour.sha256());
        assertArrayEquals(kept, Files.readAllBytes(neighbour.jar()));
        assertTrue(Files.isRegularFile(dataDir.resolve("versions").resolve("5.2").resolve("meta.json")));
        assertEquals(List.of("5.2", "6.0"),
                manager.cachedPackages().stream().map(PackageManager.CachedPackage::branch).sorted().toList());
    }

    @Test
    void deletedBranchIsDownloadedAgainOnDemand() throws Exception {
        install("5.2", "5.2.3");
        manager.delete("5.2");

        assertEquals("5.2.4",
                manager.download("5.2", publish("/5.2/nxmc-4.jar", "5.2.4", payload(1024)), null).version().full());
        assertEquals(Optional.of("5.2.4"), manager.cached("5.2").map(c -> c.version().full()));
    }

    @Test
    void cachedPackagesReportSizeAndLastUsedForTheSettingsView() throws Exception {
        byte[] payload = payload(5000);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", payload), null);
        clock.advance(Duration.ofHours(3));
        manager.markUsed("5.2");

        PackageManager.CachedPackage cached = manager.cachedPackages().get(0);
        assertEquals("5.2", cached.branch());
        assertEquals("5.2.3", cached.version().full());
        assertEquals(payload.length, cached.size());
        assertEquals(clock.instant(), cached.lastUsed());
    }

    @Test
    void installStampsTheCheckBudgetAlongsideTheLastUsedStamp() throws Exception {
        PackageManager.CachedPackage installed = manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3",
                payload(1024)), null);

        assertEquals(Optional.of(clock.instant()), installed.lastChecked());
        assertFalse(installed.updateArmed());
    }

    /**
     * An arm records no version, so an install cannot be the answer to it.
     */
    @Test
    void anInstallLeavesAnArmedBranchArmed() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp armed = checkStamp("5.2");

        clock.advance(Duration.ofHours(1));
        PackageManager.CachedPackage installed = manager.download("5.2", publish("/5.2/nxmc-4.jar", "5.2.4",
                payload(1024)), null);

        assertEquals("5.2.4", installed.version().full());
        assertTrue(installed.updateArmed(), "an install answered nothing the user asked for by hand");
        assertEquals(armed, checkStamp("5.2"), "and the request left in place is the one that was made");
    }

    /**
     * Another instance can install a newer build while the transfer runs or the prompt is open.
     */
    @Test
    void aDownloadNeverMovesTheBranchBackToAnOlderBuild() throws Exception {
        install("5.2", "5.2.5");
        byte[] kept = Files.readAllBytes(manager.cached("5.2").orElseThrow().jar());

        AtomicInteger requests = new AtomicInteger();
        byte[] older = payload(2048);
        server.createContext("/older/nxmc.jar", exchange -> {
            requests.incrementAndGet();
            respond(exchange, older);
        });

        PackageManager.CachedPackage installed = manager.download("5.2", new ReleaseManifest.Release("5.2.4",
                baseUrl() + "/older/nxmc.jar", sha256(older), older.length), null);

        assertEquals("5.2.5", installed.version().full(),
                "the newer build another instance installed is what the " + "caller gets");
        assertEquals(0, requests.get(), "and the transfer it would have replaced it with is never made");
        assertArrayEquals(kept, Files.readAllBytes(manager.cached("5.2").orElseThrow().jar()));
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    /**
     * A build killed halfway through recording is in place under the previous description, which reads as older.
     */
    @Test
    void anInstallReadsTheBranchAfterAnInterruptedInstallIsReplayed() throws Exception {
        install("5.2", "5.2.3");

        Path branch = dataDir.resolve(PackageManager.VERSIONS_DIR).resolve("5.2");
        byte[] interrupted = payload(3072);
        Files.write(branch.resolve(PackageManager.JAR_NAME), interrupted);
        writePendingMarker(branch, "5.2.5", sha256(interrupted));

        AtomicInteger requests = new AtomicInteger();
        byte[] older = payload(2048);
        server.createContext("/replayed/nxmc.jar", exchange -> {
            requests.incrementAndGet();
            respond(exchange, older);
        });

        PackageManager.CachedPackage installed = manager.download("5.2", new ReleaseManifest.Release("5.2.4",
                baseUrl() + "/replayed/nxmc.jar", sha256(older), older.length), null);

        assertEquals("5.2.5", installed.version().full(), "the build the interrupted install left is the one the " +
                "branch holds");
        assertEquals(sha256(interrupted), installed.sha256());
        assertEquals(0, requests.get(), "and the transfer it would have replaced it with is never made");
        assertArrayEquals(interrupted, Files.readAllBytes(manager.cached("5.2").orElseThrow().jar()));
        assertFalse(Files.exists(branch.resolve(PackageManager.PENDING_NAME)), "the marker is replayed, then gone");
    }

    /**
     * A skipped install still holds the lock, and a jar set aside for a rollback that never ran is the size of a build.
     */
    @Test
    void anInstallSkippedAsOlderStillClearsWhatAnInterruptedOneLeftBehind() throws Exception {
        install("5.2", "5.2.5");
        Files.write(dataDir.resolve("versions").resolve("5.2").resolve(".part-leftover"), payload(4096));

        byte[] older = payload(2048);
        PackageManager.CachedPackage installed = manager.download("5.2", new ReleaseManifest.Release("5.2.4",
                baseUrl() + "/older/nxmc.jar", sha256(older), older.length), null);

        assertEquals("5.2.5", installed.version().full());
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    /**
     * Eviction runs after an install and nowhere else, so a call that installed nothing must still run it.
     */
    @Test
    void anInstallSkippedAsOlderStillTrimsTheCacheToItsLimit() throws Exception {
        install("5.0", "5.0.7");
        clock.advance(Duration.ofMinutes(10));
        install("5.1", "5.1.2");
        clock.advance(Duration.ofMinutes(10));
        install("5.2", "5.2.5");

        plantBranch("6.0", "6.0.1");
        assertEquals(4, manager.branches().size(), "the state an install killed before its eviction leaves");

        clock.advance(Duration.ofMinutes(10));
        byte[] older = payload(2048);
        manager.download("5.2", new ReleaseManifest.Release("5.2.4", baseUrl() + "/older/nxmc.jar", sha256(older),
                older.length), null);

        assertEquals(List.of("5.1", "5.2", "6.0"), manager.branches());
    }

    @Test
    void aDownloadOfTheVersionAlreadyCachedStillInstallsIt() throws Exception {
        install("5.2", "5.2.5");

        byte[] again = payload(4096);
        PackageManager.CachedPackage installed = manager.download("5.2", publish("/again/nxmc.jar", "5.2.5", again),
                null);

        assertEquals("5.2.5", installed.version().full());
        assertEquals(sha256(again), installed.sha256());
        assertArrayEquals(again, Files.readAllBytes(manager.cached("5.2").orElseThrow().jar()));
    }

    @Test
    void markCheckedAdvancesTheCheckStampAndLeavesTheLastUsedStampAlone() throws Exception {
        install("5.2", "5.2.3");
        Instant installedAt = clock.instant();
        CheckStamp before = checkStamp("5.2");

        clock.advance(Duration.ofHours(30));
        manager.markChecked("5.2", before);

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals(Optional.of(clock.instant()), cached.lastChecked());
        assertEquals(installedAt, cached.lastUsed(), "a check is not a launch");
        assertFalse(cached.updateArmed());
    }

    @Test
    void markCheckFailedSpendsTheBudgetOfABranchNobodyArmed() throws Exception {
        install("5.2", "5.2.3");
        Instant installedAt = clock.instant();

        clock.advance(Duration.ofHours(30));
        manager.markCheckFailed("5.2");

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals(Optional.of(clock.instant()), cached.lastChecked());
        assertEquals(installedAt, cached.lastUsed(), "a check is not a launch");
    }

    @Test
    void markCheckedLeavesAnArmWrittenWhileItsFetchWasOnTheWire() throws Exception {
        install("5.2", "5.2.3");
        Instant installedAt = clock.instant();
        CheckStamp before = checkStamp("5.2");

        manager.armUpdateCheck("5.2");

        clock.advance(Duration.ofHours(30));
        manager.markChecked("5.2", before);

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertTrue(cached.updateArmed(), "a request made by hand after the check began was never answered by it");
        assertEquals(installedAt, cached.lastUsed());
    }

    @Test
    void markCheckedLeavesAnArmWrittenOverTheOneItFound() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp served = checkStamp("5.2");

        clock.advance(Duration.ofMinutes(1));
        manager.armUpdateCheck("5.2");

        clock.advance(Duration.ofHours(30));
        manager.markChecked("5.2", served);

        assertTrue(manager.cached("5.2").orElseThrow().updateArmed(),
                "the stamp served the arm it found and must " + "not" + " have taken the one written after it");
    }

    @Test
    void twoArmsInsideOneMillisecondAreStillTwoRequests() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp served = checkStamp("5.2");

        manager.armUpdateCheck("5.2");
        assertNotEquals(served, checkStamp("5.2"));

        manager.markChecked("5.2", served);
        assertTrue(manager.cached("5.2").orElseThrow().updateArmed());
    }

    /**
     * A disarm and a re-arm can both land inside the millisecond the first arm was made in.
     */
    @Test
    void anArmIsNeverAValueTheBranchAlreadyCarried() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp served = checkStamp("5.2");

        manager.markChecked("5.2", served);
        manager.armUpdateCheck("5.2");
        assertNotEquals(served, checkStamp("5.2"), "the second request has to be its own value");

        manager.markChecked("5.2", served);
        assertTrue(manager.cached("5.2").orElseThrow().updateArmed(),
                "the arm made after this check began outlives " + "it, a disarm and a re-arm in between included");
    }

    @Test
    void anArmedBranchIsDescribedByTheSameSingleNumberAnOlderLauncherReads() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");

        String description = Files.readString(dataDir.resolve("versions").resolve("5.2").resolve("meta.json"));
        assertTrue(description.matches("(?s).*\"lastChecked\"\\s*:\\s*-\\d+.*"),
                "the field has to stay one number, " + "so a launcher that reads it as a long still reads it: " + description);
    }

    @Test
    void markCheckedDisarmsTheArmItFound() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp served = checkStamp("5.2");

        clock.advance(Duration.ofHours(30));
        manager.markChecked("5.2", served);

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertFalse(cached.updateArmed(), "the check served the request that armed the branch");
        assertEquals(Optional.of(clock.instant()), cached.lastChecked());
    }

    @Test
    void markCheckFailedLeavesAnArmedBranchArmed() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");

        clock.advance(Duration.ofHours(30));
        manager.markCheckFailed("5.2");

        assertTrue(manager.cached("5.2").orElseThrow().updateArmed(),
                "a request the user made by hand must outlive " + "a" + " check that learned nothing");
    }

    @Test
    void markUsedLeavesTheCheckStampAlone() throws Exception {
        install("5.2", "5.2.3");
        Instant installedAt = clock.instant();

        clock.advance(Duration.ofHours(30));
        manager.markUsed("5.2");

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals(clock.instant(), cached.lastUsed());
        assertEquals(Optional.of(installedAt), cached.lastChecked(), "a launch is not a check");
    }

    @Test
    void anArmedBranchStaysArmedUntilTheNextCheck() throws Exception {
        install("5.2", "5.2.3");
        Instant installedAt = clock.instant();

        manager.armUpdateCheck("5.2");
        PackageManager.CachedPackage armed = manager.cached("5.2").orElseThrow();
        assertTrue(armed.updateArmed());
        assertTrue(armed.lastChecked().isEmpty(), "an armed branch has no check time to report");
        assertEquals(installedAt, armed.lastUsed());

        clock.advance(Duration.ofHours(1));
        manager.markUsed("5.2");
        assertTrue(manager.cached("5.2").orElseThrow().updateArmed(), "a launch must not disarm a check the user " +
                "asked for");

        manager.markChecked("5.2", armed.checkStamp());
        assertFalse(manager.cached("5.2").orElseThrow().updateArmed());
    }

    @Test
    void aDescriptionWrittenBeforeTheCheckStampExistedReadsAsNeverChecked() throws Exception {
        install("5.2", "5.2.3");
        Path branch = dataDir.resolve("versions").resolve("5.2");
        byte[] jar = Files.readAllBytes(branch.resolve("nxmc-standalone.jar"));

        Files.writeString(branch.resolve("meta.json"),
                "{\"version\":\"5.2.3\",\"sha256\":\"" + sha256(jar) + "\"," + "\"lastUsed\":1}");

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full(), "an older description has to stay a cache hit");
        assertTrue(cached.lastChecked().isEmpty());
        assertFalse(cached.updateArmed(), "zero is never checked; only a value no such file can hold means armed");
    }

    @Test
    void aDescriptionCarryingAFieldTheRecordNoLongerHasStaysACacheHit() throws Exception {
        install("5.2", "5.2.3");
        Path branch = dataDir.resolve("versions").resolve("5.2");
        byte[] jar = Files.readAllBytes(branch.resolve("nxmc-standalone.jar"));

        Files.writeString(branch.resolve("meta.json"),
                "{\"version\":\"5.2.3\",\"sha256\":\"" + sha256(jar) + "\"," + "\"lastUsed\":1,\"lastChecked\":2," +
                        "\"declinedBuild\":\"5.2.4\"}");

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full());
        assertEquals(sha256(jar), cached.sha256());
        assertEquals(Instant.ofEpochMilli(2), cached.lastChecked().orElseThrow());
    }

    @Test
    void aRecoveredInstallKeepsTheCheckStampItsMarkerDescribes() throws Exception {
        install("5.2", "5.2.3");

        clock.advance(Duration.ofHours(2));
        Instant checked = clock.instant();
        byte[] installed = payload(2048);
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.write(branch.resolve("nxmc-standalone.jar"), installed);
        Files.writeString(branch.resolve(".pending-meta.json"),
                "{\"version\":\"5.2.4\",\"sha256\":\"" + sha256(installed) + "\",\"lastUsed\":1,\"lastChecked\":" + checked.toEpochMilli() + "}");

        clock.advance(Duration.ofHours(3));
        manager.cleanupStaleTemporaryFiles();

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.4", cached.version().full());
        assertEquals(Optional.of(checked), cached.lastChecked(),
                "recovery relabels the jar, it does not check the " + "branch");
    }

    @Test
    void aRecoveredInstallKeepsAnArmWrittenAfterItsMarker() throws Exception {
        install("5.2", "5.2.3");

        byte[] installed = payload(2048);
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.write(branch.resolve("nxmc-standalone.jar"), installed);
        String description =
                "{\"version\":\"5.2.4\",\"sha256\":\"" + sha256(installed) + "\",\"lastUsed\":" + clock.millis() + ","
                        + "\"lastChecked\":" + clock.millis() + "}";
        Files.writeString(branch.resolve("meta.json"), description);
        Files.writeString(branch.resolve(".pending-meta.json"), description);

        manager.armUpdateCheck("5.2");
        CheckStamp armed = checkStamp("5.2");

        manager.cleanupStaleTemporaryFiles();

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.4", cached.version().full(), "the marker still names the build");
        assertEquals(armed, cached.checkStamp(), "a replay must not drop a request nobody has answered yet");
    }

    /**
     * The same window the other way round: the marker carries an arm a check has since served.
     */
    @Test
    void aReplayDoesNotBringBackAnArmAnAnsweredCheckHasServed() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp armed = checkStamp("5.2");

        byte[] installed = payload(2048);
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.write(branch.resolve("nxmc-standalone.jar"), installed);
        String description =
                "{\"version\":\"5.2.4\",\"sha256\":\"" + sha256(installed) + "\",\"lastUsed\":" + clock.millis() + ","
                        + "\"lastChecked\":" + armed.stored() + "}";
        Files.writeString(branch.resolve("meta.json"), description);
        Files.writeString(branch.resolve(".pending-meta.json"), description);

        clock.advance(Duration.ofHours(1));
        manager.markChecked("5.2", armed);
        CheckStamp checked = checkStamp("5.2");

        manager.cleanupStaleTemporaryFiles();

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.4", cached.version().full(), "the marker still names the build");
        assertFalse(cached.updateArmed(), "the replay brought back a request that had already been served");
        assertEquals(checked, cached.checkStamp());
    }

    /**
     * And with the clock separating none of it: an arm and the check that served it can share one moment.
     */
    @Test
    void aReplayReadsAStampMadeInsideTheArmsOwnMillisecondAsTheLaterOne() throws Exception {
        install("5.2", "5.2.3");
        manager.armUpdateCheck("5.2");
        CheckStamp armed = checkStamp("5.2");

        byte[] installed = payload(2048);
        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.write(branch.resolve("nxmc-standalone.jar"), installed);
        String description =
                "{\"version\":\"5.2.4\",\"sha256\":\"" + sha256(installed) + "\",\"lastUsed\":" + clock.millis() + ","
                        + "\"lastChecked\":" + armed.stored() + "}";
        Files.writeString(branch.resolve("meta.json"), description);
        Files.writeString(branch.resolve(".pending-meta.json"), description);

        manager.markChecked("5.2", armed);
        CheckStamp checked = checkStamp("5.2");

        manager.cleanupStaleTemporaryFiles();

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertFalse(cached.updateArmed(), "the request was served inside the millisecond it was made in");
        assertEquals(checked, cached.checkStamp());
    }

    @Test
    void bothCheckStampsReportBusyWhileAnotherInstanceHoldsTheBranch() throws Exception {
        install("5.2", "5.2.3");

        PackageManager other = newManager();
        CheckStamp stamp = checkStamp("5.2");
        try (PackageManager.BranchLock held = manager.lockBranch("5.2")) {
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.markChecked("5.2", stamp)).kind());
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.markCheckFailed("5.2")).kind());
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.armUpdateCheck("5.2")).kind());
        }

        other.markChecked("5.2", stamp);
        assertEquals(Optional.of(clock.instant()), other.cached("5.2").orElseThrow().lastChecked());
    }

    @Test
    void neitherCheckStampWritesThroughASymbolicLinkInABranchsPlace() throws Exception {
        Path outside = dataDir.resolve("elsewhere");
        Files.createDirectories(outside);
        String meta = "{\"version\":\"5.2.3\",\"sha256\":\"00\",\"lastUsed\":1}";
        Files.writeString(outside.resolve("nxmc-standalone.jar"), "planted");
        Files.writeString(outside.resolve("meta.json"), meta);

        Path link = dataDir.resolve("versions").resolve("5.2");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            abort("no symbolic links on this platform, so there is nothing to protect against");
        }

        manager.markChecked("5.2", CheckStamp.NEVER);
        manager.markCheckFailed("5.2");
        assertFalse(manager.armUpdateCheck("5.2"), "an entry that is not a branch of ours took no request");

        assertEquals(meta, Files.readString(outside.resolve("meta.json")), "a check stamp wrote through the link");
    }

    /**
     * A branch that went away during the fetch has to be as visible to the caller as a write the cache refused.
     */
    @Test
    void armUpdateCheckReportsABranchThatIsNoLongerCached() throws Exception {
        install("5.2", "5.2.3");
        assertTrue(manager.armUpdateCheck("5.2"), "a cached build takes the request");

        manager.delete("5.2");
        assertFalse(manager.armUpdateCheck("5.2"), "a branch that is gone carries nothing");
        assertFalse(manager.armUpdateCheck("5.9"), "and neither does one that was never cached");
    }

    /**
     * A description is not a build: both stamps report the question {@code cached} answers, not what {@code meta
     * .json} holds.
     */
    @Test
    void neitherStampLandsOnADescriptionWhoseBuildIsGone() throws Exception {
        install("5.2", "5.2.3");
        CheckStamp stamp = checkStamp("5.2");
        Files.delete(dataDir.resolve("versions").resolve("5.2").resolve("nxmc-standalone.jar"));

        assertTrue(manager.cached("5.2").isEmpty(), "a description without its jar is a cache miss");
        assertFalse(manager.armUpdateCheck("5.2"), "an arm needs a build to carry it");
        assertFalse(manager.markChecked("5.2", stamp), "and a schedule needs one to steer");
    }

    /**
     * The stamp that follows the answer is also what says the build it was about is still there.
     */
    @Test
    void markCheckedReportsABranchThatIsNoLongerCached() throws Exception {
        install("5.2", "5.2.3");
        CheckStamp stamp = checkStamp("5.2");
        assertTrue(manager.markChecked("5.2", stamp), "a cached build takes the schedule");

        manager.delete("5.2");
        assertFalse(manager.markChecked("5.2", stamp), "a branch that is gone holds no schedule");
    }

    @Test
    void clearCacheRemovesEverything() throws Exception {
        install("5.1", "5.1.2");
        install("5.2", "5.2.3");

        manager.clearCache();

        assertTrue(manager.branches().isEmpty());
        assertTrue(manager.cachedPackages().isEmpty());
    }

    @Test
    void aBranchThatIsASymbolicLinkIsNeverEnumeratedOrClearedThrough() throws Exception {
        install("5.2", "5.2.3");

        Path outside = dataDir.resolve("elsewhere");
        Files.createDirectories(outside);
        Path bystander = outside.resolve("important.txt");
        Files.writeString(bystander, "not ours to delete");
        Files.writeString(outside.resolve(".part-leftover"), "not ours either");

        Path link = dataDir.resolve("versions").resolve("6.0");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            abort("no symbolic links on this platform, so there is nothing to protect against");
        }

        assertEquals(List.of("5.2"), manager.branches());
        assertEquals(List.of("5.2", "6.0"), manager.cacheEntries());
        manager.cleanupStaleTemporaryFiles();
        manager.clearCache();

        assertTrue(Files.isRegularFile(bystander), "clearing the cache deleted a file outside it");
        assertTrue(Files.isRegularFile(outside.resolve(".part-leftover")),
                "the startup sweep reached through the " + "link");

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "clearing the cache left the link " + "behind");
        assertTrue(manager.cacheEntries().isEmpty());
        assertFalse(manager.delete("6.0"), "the link was already removed");
    }

    @Test
    void clearingTheCacheRemovesAnEntryThatBlocksAnInstall() throws Exception {
        install("5.1", "5.1.2");

        Path blocked = dataDir.resolve("versions").resolve("5.2");
        Files.writeString(blocked, "not a branch directory");

        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload(1024));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> manager.download(
                "5.2", release, null)).kind());
        assertTrue(manager.cachedPackages().stream().noneMatch(c -> c.branch().equals("5.2")));
        assertEquals(List.of("5.1", "5.2"), manager.cacheEntries());

        manager.clearCache();

        assertTrue(manager.cacheEntries().isEmpty());
        assertEquals("5.2.3", manager.download("5.2", release, null).version().full());
    }

    @Test
    void aBranchThatIsASymbolicLinkIsNeitherInstalledIntoNorLaunchedFrom() throws Exception {
        Path outside = dataDir.resolve("elsewhere");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("nxmc-standalone.jar"), "planted");
        Files.writeString(outside.resolve("meta.json"), "{\"version\":\"5.2.3\",\"sha256\":\"00\",\"lastUsed\":1}");

        Path link = dataDir.resolve("versions").resolve("5.2");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            abort("no symbolic links on this platform, so there is nothing to protect against");
        }

        assertTrue(manager.cached("5.2").isEmpty(), "a link in a branch's place read back as a cached build");
        manager.markUsed("5.2");
        assertEquals("{\"version\":\"5.2.3\",\"sha256\":\"00\",\"lastUsed\":1}", Files.readString(outside.resolve(
                "meta.json")));

        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload(1024));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class, () -> manager.download(
                "5.2", release, null)).kind());

        assertEquals("planted", Files.readString(outside.resolve("nxmc-standalone.jar")), "the install wrote through "
                + "the link");
        assertTrue(temporaryFiles(outside).isEmpty(), "the install left temporary files outside the cache");
    }

    @Test
    void aBranchIsClearedThroughTheDirectoryThatWasOpened() throws Exception {
        install("5.2", "5.2.3");

        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.createDirectory(branch.resolve("nested"));
        Files.writeString(branch.resolve(".part-leftover"), "leftover");

        assertTrue(manager.delete("5.2"));
        assertFalse(Files.exists(branch));
    }

    @Test
    void theStartupSweepClearsTemporaryFilesOfARealBranch() throws Exception {
        install("5.2", "5.2.3");

        Path branch = dataDir.resolve("versions").resolve("5.2");
        Files.writeString(branch.resolve(".part-leftover"), "leftover");
        Files.writeString(branch.resolve("meta.json.tmp"), "{}");

        manager.cleanupStaleTemporaryFiles();

        assertFalse(Files.exists(branch.resolve(".part-leftover")));
        assertFalse(Files.exists(branch.resolve("meta.json.tmp")));
        assertTrue(manager.cached("5.2").isPresent());
    }

    @Test
    void aStalledDownloadFailsInsteadOfHoldingTheBranchForever() throws Exception {
        byte[] payload = payload(4096);
        server.createContext("/5.2/stalled.jar", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload, 0, 16);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        PackageManager stalling = new PackageManager(dataDir, clock, HttpClient.newHttpClient(),
                Duration.ofMillis(300));
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/stalled.jar",
                sha256(payload), payload.length);

        PackageException e = assertThrows(PackageException.class, () -> stalling.download("5.2", release, null));
        assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
        assertTrue(e.getMessage().contains("sent nothing"), e.getMessage());
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
        try (PackageManager.BranchLock lock = manager.lockBranch("5.2")) {
            assertNotNull(lock);
        }
    }

    @Test
    void aServerThatAnswersNothingFailsOnTheHeaderDeadline() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/5.2/silent.jar", exchange -> {
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        PackageManager impatient = new PackageManager(dataDir, clock, HttpClient.newHttpClient(),
                Duration.ofSeconds(30), Duration.ofMillis(300));
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/silent.jar",
                "a".repeat(64), 4096);

        try {
            long started = System.nanoTime();
            PackageException e = assertThrows(PackageException.class, () -> impatient.download("5.2", release, null));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(PackageException.Kind.DOWNLOAD_FAILED, e.kind());
            assertTrue(e.getMessage().contains("no response headers"), e.getMessage());
            assertTrue(waited.toSeconds() < 10, "the header deadline waited " + waited.toMillis() + " ms");
            assertTrue(manager.cached("5.2").isEmpty());
            assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
            try (PackageManager.BranchLock lock = manager.lockBranch("5.2")) {
                assertNotNull(lock);
            }
        } finally {
            finish.countDown();
        }
    }

    /**
     * {@code HttpRequest.timeout} covers the body too from JDK 26 on (JDK-8370631), so a download must not carry one.
     */
    @Test
    void theDownloadRequestCarriesNoRequestTimeout() throws Exception {
        byte[] payload = payload(2048);
        URI url = URI.create("https://netxms.org/5.2/nxmc.jar");
        CompletedExchangeClient client = new CompletedExchangeClient(new StubResponse(url,
                new ByteArrayInputStream(payload)));
        PackageManager offline = new PackageManager(dataDir, clock, client);
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", url.toString(), sha256(payload),
                payload.length);

        assertEquals("5.2.3", offline.download("5.2", release, null).version().full());
        assertTrue(client.lastRequest().timeout().isEmpty(),
                "a request timeout would bound the transfer itself on " + "JDK 26");
    }

    @Test
    void aCancelledDownloadStopsMidStreamAndLeavesTheBranchAsItFoundIt() throws Exception {
        byte[] payload = payload(300_000);
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload);

        AtomicBoolean cancel = new AtomicBoolean();
        List<Long> progress = new ArrayList<>();
        PackageException e = assertThrows(PackageException.class, () -> manager.download("5.2", release, (done,
                                                                                                          total) -> {
            progress.add(done);
            cancel.set(true);
        }, cancel::get));

        assertEquals(PackageException.Kind.CANCELLED, e.kind());
        assertEquals(1, progress.size(), "the download must stop at the first chunk, not run to the end");
        assertTrue(progress.get(0) < payload.length);

        Path branch = dataDir.resolve("versions").resolve("5.2");
        assertTrue(manager.cached("5.2").isEmpty());
        assertFalse(Files.exists(branch.resolve("nxmc-standalone.jar")));
        assertFalse(Files.exists(branch.resolve("meta.json")));
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
        assertFalse(Files.exists(branch));
        assertTrue(manager.cacheEntries().isEmpty(), manager.cacheEntries().toString());

        assertEquals("5.2.3", manager.download("5.2", release, null).version().full());
    }

    @Test
    void aCancelledDownloadLeavesAPreviouslyCachedBuildIntact() throws Exception {
        byte[] good = payload(2048);
        manager.download("5.2", publish("/5.2/nxmc.jar", "5.2.3", good), null);

        ReleaseManifest.Release newer = publish("/5.2/nxmc-4.jar", "5.2.4", payload(300_000));
        AtomicBoolean cancel = new AtomicBoolean(true);

        assertEquals(PackageException.Kind.CANCELLED, assertThrows(PackageException.class,
                () -> manager.download("5" + ".2", newer, null, cancel::get)).kind());

        PackageManager.CachedPackage cached = manager.cached("5.2").orElseThrow();
        assertEquals("5.2.3", cached.version().full());
        assertArrayEquals(good, Files.readAllBytes(cached.jar()));
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    @Test
    void aCancelEndsARequestTheServerHasNotAnsweredYet() throws Exception {
        byte[] payload = payload(4096);
        int chunks = 1024;
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        AtomicBoolean connectionGone = new AtomicBoolean();
        server.createContext("/5.2/silent.jar", exchange -> {
            requested.countDown();
            try {
                answer.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, (long) chunks * payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < chunks; i++) {
                        out.write(payload);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                connectionGone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                answered.countDown();
            }
        });

        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/silent.jar",
                sha256(payload), payload.length);
        AtomicBoolean cancel = new AtomicBoolean();
        Thread presser = new Thread(() -> {
            try {
                requested.await(10, TimeUnit.SECONDS);
                cancel.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        presser.setDaemon(true);
        presser.start();

        try {
            long started = System.nanoTime();
            PackageException e = assertThrows(PackageException.class, () -> manager.download("5.2", release, null,
                    cancel::get));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(PackageException.Kind.CANCELLED, e.kind());
            assertTrue(waited.toSeconds() < 5, "cancel waited " + waited.toMillis() + " ms for the response");
            assertTrue(manager.cached("5.2").isEmpty());
            assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
            try (PackageManager.BranchLock lock = manager.lockBranch("5.2")) {
                assertNotNull(lock);
            }
        } finally {
            answer.countDown();
            presser.join(1000);
        }

        assertTrue(answered.await(10, TimeUnit.SECONDS), "server never finished its reply");
        assertTrue(connectionGone.get(), "cancelled request stayed on the wire");
    }

    @Test
    void aCancelThatLosesToTheHeadersStillGivesTheConnectionBack() throws Exception {
        byte[] payload = payload(4096);
        int chunks = 1024;
        CountDownLatch answer = new CountDownLatch(1);
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        AtomicBoolean connectionGone = new AtomicBoolean();
        server.createContext("/5.2/raced.jar", exchange -> {
            try {
                answer.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, (long) chunks * payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                    out.flush();
                    headersSent.countDown();
                    for (int i = 1; i < chunks; i++) {
                        out.write(payload);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                connectionGone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                headersSent.countDown();
                answered.countDown();
            }
        });

        AtomicBoolean pressed = new AtomicBoolean();
        AtomicBoolean sawHeaders = new AtomicBoolean();
        CancelToken cancel = () -> {
            if (pressed.getAndSet(true)) {
                return true;
            }
            answer.countDown();
            try {
                sawHeaders.set(headersSent.await(10, TimeUnit.SECONDS));
                Thread.sleep(500);   // the client completes the response on a thread of its own
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };

        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/raced.jar",
                sha256(payload), payload.length);
        try {
            assertEquals(PackageException.Kind.CANCELLED, assertThrows(PackageException.class,
                    () -> manager.download("5.2", release, null, cancel)).kind());
            assertTrue(manager.cached("5.2").isEmpty());
            assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
        } finally {
            answer.countDown();
        }

        assertTrue(sawHeaders.get(), "the server never got to send its headers");

        assertTrue(answered.await(10, TimeUnit.SECONDS), "the response body was left open");
        assertTrue(connectionGone.get(), "the body of the lost cancel stayed open");
    }

    @Test
    void closesTheBodyOfAnExchangeThatCompletedBeforeTheCancel() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        URI url = URI.create("https://netxms.org/5.2/nxmc.jar");
        PackageManager raced = new PackageManager(dataDir, clock, new CompletedExchangeClient(new StubResponse(url,
                body)));
        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", url.toString(), "a".repeat(64), 1024);

        assertEquals(PackageException.Kind.CANCELLED, assertThrows(PackageException.class, () -> raced.download("5.2"
                , release, null, () -> true)).kind());
        assertTrue(closed.get(), "the body of an exchange the cancel could not take down was left open");
        assertTrue(manager.cached("5.2").isEmpty());
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    @Test
    void aCancelEndsAReadTheBodyHasStoppedFeeding() throws Exception {
        byte[] payload = payload(300_000);
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/5.2/quiet.jar", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            OutputStream out = exchange.getResponseBody();
            out.write(payload, 0, 16);
            out.flush();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });

        ReleaseManifest.Release release = new ReleaseManifest.Release("5.2.3", baseUrl() + "/5.2/quiet.jar",
                sha256(payload), payload.length);
        PackageManager patient = new PackageManager(dataDir, clock, HttpClient.newHttpClient(), Duration.ofSeconds(30));
        AtomicBoolean cancel = new AtomicBoolean();

        try {
            long started = System.nanoTime();
            PackageException e = assertThrows(PackageException.class, () -> patient.download("5.2", release, (done,
                                                                                                              total) -> cancel.set(true), cancel::get));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(PackageException.Kind.CANCELLED, e.kind());
            assertTrue(waited.toSeconds() < 10, "cancel waited " + waited.toMillis() + " ms for the blocked read");
            assertTrue(manager.cached("5.2").isEmpty());
            assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
        } finally {
            finish.countDown();
        }
    }

    @Test
    void aCancelOverTheLastChunkStillInstallsTheCompletedDownload() throws Exception {
        byte[] payload = payload(300_000);
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload);

        AtomicBoolean cancel = new AtomicBoolean();
        PackageManager.CachedPackage installed = manager.download("5.2", release, (done, total) -> {
            if (done == total) {
                cancel.set(true);
            }
        }, cancel::get);

        assertEquals("5.2.3", installed.version().full());
        assertEquals(sha256(payload), installed.sha256());
        assertArrayEquals(payload, Files.readAllBytes(installed.jar()));
        assertTrue(temporaryFiles("5.2").isEmpty(), temporaryFiles("5.2").toString());
    }

    @Test
    void aTokenThatNeverTripsLeavesTheDownloadUnchanged() throws Exception {
        byte[] payload = payload(300_000);
        ReleaseManifest.Release release = publish("/5.2/nxmc.jar", "5.2.3", payload);

        PackageManager.CachedPackage explicit = manager.download("5.2", release, null, CancelToken.NONE);
        assertEquals("5.2.3", explicit.version().full());
        assertArrayEquals(payload, Files.readAllBytes(explicit.jar()));

        PackageManager.CachedPackage implied = manager.download("5.2", release, null);
        assertEquals(sha256(payload), implied.sha256());
        assertArrayEquals(payload, Files.readAllBytes(implied.jar()));
        assertTrue(temporaryFiles("5.2").isEmpty());
    }

    @Test
    void treatsBrokenMetadataAsCacheMiss() throws Exception {
        install("5.2", "5.2.3");
        Path meta = dataDir.resolve("versions").resolve("5.2").resolve("meta.json");

        Files.writeString(meta, "{ not json");
        assertTrue(manager.cached("5.2").isEmpty());

        Files.writeString(meta, "{ \"version\": \"garbage\", \"sha256\": \"x\", \"lastUsed\": 1 }");
        assertTrue(manager.cached("5.2").isEmpty());

        Files.delete(meta);
        assertTrue(manager.cached("5.2").isEmpty());
    }

    @Test
    void treatsMissingJarAsCacheMiss() throws Exception {
        install("5.2", "5.2.3");
        Files.delete(dataDir.resolve("versions").resolve("5.2").resolve("nxmc-standalone.jar"));
        assertTrue(manager.cached("5.2").isEmpty());
    }

    private void install(String branch, String version) throws Exception {
        manager.download(branch, publish("/" + version + "/nxmc.jar", version, payload(1024)), null);
    }

    private void plantBranch(String branch, String version) throws Exception {
        Path dir = dataDir.resolve("versions").resolve(branch);
        Files.createDirectories(dir);
        byte[] jar = payload(1024);
        Files.write(dir.resolve("nxmc-standalone.jar"), jar);
        Files.writeString(dir.resolve("meta.json"), "{\"version\":\"" + version + "\",\"sha256\":\"" + sha256(jar) +
                "\",\"lastUsed\":" + clock.millis() + ",\"lastChecked\":" + clock.millis() + "}");
    }

    private CheckStamp checkStamp(String branch) {
        return manager.cached(branch).orElseThrow().checkStamp();
    }

    private ReleaseManifest.Release publish(String path, String version, byte[] payload) throws IOException {
        server.createContext(path, exchange -> respond(exchange, payload));
        return new ReleaseManifest.Release(version, baseUrl() + path, sha256(payload), payload.length);
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    private List<Path> temporaryFiles(String branch) throws IOException {
        return temporaryFiles(dataDir.resolve("versions").resolve(branch));
    }

    private static final class CompletedExchangeClient extends HttpClient {
        private final HttpResponse<InputStream> response;
        private volatile HttpRequest lastRequest;

        CompletedExchangeClient(HttpResponse<InputStream> response) {
            this.response = response;
        }

        HttpRequest lastRequest() {
            return lastRequest;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler) {
            lastRequest = request;
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record StubResponse(URI uri, InputStream body) implements HttpResponse<InputStream> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-07-23T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
