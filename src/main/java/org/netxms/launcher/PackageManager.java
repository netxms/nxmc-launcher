package org.netxms.launcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PackageManager {
    public static final int MAX_CACHED_BRANCHES = 3;

    static final String VERSIONS_DIR = "versions";
    static final String LOCKS_DIR = ".locks";
    static final String JAR_NAME = "nxmc-standalone.jar";
    static final String META_NAME = "meta.json";
    static final String LOCK_SUFFIX = ".lock";
    static final String PART_PREFIX = ".part-";
    static final String META_TEMP_SUFFIX = ".tmp";
    static final String PENDING_NAME = ".pending-meta.json";
    static final String SHARED_LOCK_PROBE = ".shared-locks";
    // named in full because Manifest-Version is a different, real attribute of the same file
    static final String PACKAGE_VERSION_ATTRIBUTE = "Package-Version";
    static final String NXMC_MAIN_CLASS = "org.netxms.nxmc.BootstrapLoader";
    static final Duration HEADER_TIMEOUT = Duration.ofSeconds(60);
    static final Duration STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final Pattern BRANCH_KEY = Pattern.compile("\\d+\\.\\d+");
    private static final int BUFFER_SIZE = 65536;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CANCEL_POLL = Duration.ofMillis(100);
    private static final int PIN_ATTEMPTS = 30;
    private static final long PIN_RETRY_MILLIS = 100L;
    private static final Object SHARED_LOCK_PROBE_MONITOR = new Object();

    private final Path root;
    private final Path locks;
    private final Clock clock;
    private final HttpClient httpClient;
    private final Duration stallTimeout;
    private final Duration headerTimeout;
    private volatile Boolean sharedLocks;

    public PackageManager(Path dataDir) {
        this(dataDir, Clock.systemUTC(),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    PackageManager(Path dataDir, Clock clock, HttpClient httpClient) {
        this(dataDir, clock, httpClient, STALL_TIMEOUT);
    }

    PackageManager(Path dataDir, Clock clock, HttpClient httpClient, Duration stallTimeout) {
        this(dataDir, clock, httpClient, stallTimeout, HEADER_TIMEOUT);
    }

    PackageManager(Path dataDir, Clock clock, HttpClient httpClient, Duration stallTimeout, Duration headerTimeout) {
        this.root = dataDir.resolve(VERSIONS_DIR);
        this.locks = this.root.resolve(LOCKS_DIR);
        this.clock = clock;
        this.httpClient = httpClient;
        this.stallTimeout = stallTimeout;
        this.headerTimeout = headerTimeout;
    }

    private static boolean describesBuild(Path dir, Meta meta) {
        return Files.isRegularFile(dir.resolve(JAR_NAME)) && ServerVersion.parse(meta.version()).isPresent();
    }

    private static boolean holdsSomethingNewerThan(Optional<CachedPackage> present, Optional<ServerVersion> offered) {
        return present.isPresent() && offered.isPresent() && present.get().version().isNewerPatchThan(offered.get());
    }

    private static Identity copyAndDigest(IdentifiedBuild build, Path temp) throws PackageException {
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(build.source()); OutputStream out = Files.newOutputStream(temp
                    , StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            hash = HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR,
                    "Cannot copy " + build.source() + " into " + "the cache: " + e.getMessage(), e);
        }

        ServerVersion copied = nxmcVersion(temp, "The copy of " + build.source());
        if (!copied.full().equals(build.version().full())) {
            throw invalidPackage(build.source() + " now holds version " + copied.full() + " rather than the " + build.version().full() + " it was read as, so it changed while it was being installed");
        }

        return new Identity(build.version().full(), hash);
    }

    private static ServerVersion nxmcVersion(Path file, String subject) throws PackageException {
        try (JarFile jar = new JarFile(file.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw invalidPackage(subject + " has no META-INF/MANIFEST.MF, so it is not an nxmc build");
            }

            return nxmcVersion(manifest.getMainAttributes(), subject);
        } catch (IOException e) {
            throw invalidPackage(subject + " cannot be read as a jar file: " + e.getMessage(), e);
        }
    }

    private static ServerVersion nxmcVersion(Attributes attributes, String subject) throws PackageException {
        String mainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
        if (!NXMC_MAIN_CLASS.equals(mainClass)) {
            throw invalidPackage(subject + " is not an nxmc standalone build: it starts " + ((mainClass != null) ?
                    mainClass : "no main class") + " rather than " + NXMC_MAIN_CLASS);
        }

        String version = attributes.getValue(PACKAGE_VERSION_ATTRIBUTE);
        if (version == null) {
            throw invalidPackage(subject + " has no " + PACKAGE_VERSION_ATTRIBUTE + " in its manifest, so the build " + "it holds cannot be named");
        }

        ServerVersion parsed = ServerVersion.parse(version).orElseThrow(() -> invalidPackage(subject + " names version \"" + version + "\", which is not a version a branch can be derived from"));
        if (!parsed.isSupported()) {
            throw invalidPackage(subject + " names version " + parsed.full() + ", and builds before nxmc " + ServerVersion.MINIMUM_SUPPORTED_MAJOR + ".0.0 cannot be started with the authentication token the launcher hands over");
        }

        return parsed;
    }

    private static Meta promoted(Meta marker, Meta current) {
        return (current == null) ? marker : marker.stamped(marker.lastChecked().orLater(current.lastChecked()));
    }

    private static boolean isBranch(Path dir) {
        return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
    }

    private static void abandon(CompletableFuture<HttpResponse<InputStream>> exchange) {
        if (exchange.cancel(true)) {
            return;
        }

        HttpResponse<InputStream> response;
        try {
            response = exchange.getNow(null);
        } catch (CancellationException | CompletionException e) {
            return;
        }
        if (response == null) {
            return;
        }

        try {
            response.body().close();
        } catch (IOException ignored) {
        }
    }

    private static PackageException cancelled(URI url) {
        return new PackageException(PackageException.Kind.CANCELLED, "Download of " + url + " cancelled");
    }

    private static String sha256(Path file) throws PackageException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR, "Cannot read " + file + " to check its " +
                    "checksum: " + e.getMessage(), e);
        }
    }

    private static Meta readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try {
            Meta meta = MAPPER.readValue(file.toFile(), Meta.class);
            return ((meta != null) && (meta.version() != null)) ? meta : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static void createBranchDir(Path dir) throws PackageException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !isBranch(dir)) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR, "Cache entry " + dir + " is not a " +
                    "directory" + " this launcher created, so no build can be installed into it");
        }

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR, "Cannot create cache directory " + dir +
                    ":" + " " + e.getMessage(), e);
        }
    }

    static Path setAside(Path dir, Path jar) throws PackageException {
        if (!Files.isRegularFile(jar)) {
            return null;
        }

        try {
            Path backup = Files.createTempFile(dir, PART_PREFIX, "");
            try {
                Files.delete(backup);
                Files.createLink(backup, jar);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(jar, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            return backup;
        } catch (IOException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR,
                    "Cannot set the cached build " + jar + " " + "aside: " + e.getMessage(), e);
        }
    }

    private static boolean isTemporary(String name) {
        return name.startsWith(PART_PREFIX) || name.endsWith(META_TEMP_SUFFIX);
    }

    private static boolean sameOrigin(URI published, URI actual) {
        return (actual != null) && equalsIgnoreCase(published.getScheme(), actual.getScheme()) && equalsIgnoreCase(published.getHost(), actual.getHost()) && (port(published) == port(actual));
    }

    static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return (a != null) && a.equalsIgnoreCase(b);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static void deleteIfEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private static PackageException busy(String branch) {
        return new PackageException(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER,
                "Another launcher instance is " + "downloading, starting or removing the build for branch " + branch);
    }

    private static PackageException downloadFailed(URI url, String reason, Throwable cause) {
        return new PackageException(PackageException.Kind.DOWNLOAD_FAILED, "Cannot download " + url + ": " + reason,
                cause);
    }

    private static PackageException invalidPackage(String message) {
        return new PackageException(PackageException.Kind.INVALID_PACKAGE, message);
    }

    private static PackageException invalidPackage(String message, Throwable cause) {
        return new PackageException(PackageException.Kind.INVALID_PACKAGE, message, cause);
    }

    public Path root() {
        return root;
    }

    public Optional<CachedPackage> cached(String branch) {
        Path dir = branchDir(branch);
        if (!isBranch(dir)) {
            return Optional.empty();
        }

        Meta meta = readMeta(dir);
        if ((meta == null) || !describesBuild(dir, meta)) {
            return Optional.empty();
        }

        Path jar = dir.resolve(JAR_NAME);
        try {
            return Optional.of(new CachedPackage(branch, jar, ServerVersion.parse(meta.version()).orElseThrow(),
                    meta.sha256(), Instant.ofEpochMilli(meta.lastUsed()), Files.size(jar), meta.lastChecked()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<CachedPackage> cachedPackages() {
        List<CachedPackage> packages = new ArrayList<>();
        for (String branch : branches())
            cached(branch).ifPresent(packages::add);
        packages.sort(Comparator.comparing(CachedPackage::lastUsed).reversed());
        return packages;
    }

    public void markUsed(String branch) throws PackageException {
        restamp(branch, meta -> new Meta(meta.version(), meta.sha256(), clock.millis(), meta.lastChecked()));
    }

    public boolean markChecked(String branch, CheckStamp servedArm) throws PackageException {
        return restamp(branch, meta -> (meta.lastChecked().armed() && !meta.lastChecked().equals(servedArm)) ? meta :
                meta.stamped(meta.lastChecked().check(clock.millis())));
    }

    public void markCheckFailed(String branch) throws PackageException {
        restamp(branch, meta -> meta.lastChecked().armed() ? meta :
                meta.stamped(meta.lastChecked().check(clock.millis())));
    }

    public boolean armUpdateCheck(String branch) throws PackageException {
        return restamp(branch, meta -> meta.stamped(meta.lastChecked().arm(clock.millis())));
    }

    private boolean restamp(String branch, UnaryOperator<Meta> stamp) throws PackageException {
        try (BranchLock lock = lockBranch(branch)) {
            Path dir = branchDir(branch);
            if (!isBranch(dir)) {
                return false;
            }

            Meta meta = readMeta(dir);
            if ((meta == null) || !describesBuild(dir, meta)) {
                return false;
            }

            Meta stamped = stamp.apply(meta);
            if (!stamped.equals(meta)) {
                writeMeta(dir, stamped);
            }
            return true;
        }
    }

    public CachedPackage download(String branch, ReleaseManifest.Release release, ProgressListener progress) throws PackageException {
        return download(branch, release, progress, CancelToken.NONE);
    }

    public CachedPackage download(String branch, ReleaseManifest.Release release, ProgressListener progress,
                                  CancelToken cancel) throws PackageException {
        return install(branch, temp -> {
            streamTo(release, temp, (progress != null) ? progress : (done, total) -> {
            }, cancel);

            String actual = sha256(temp);
            if (!actual.equals(release.sha256().toLowerCase(Locale.ROOT))) {
                throw new PackageException(PackageException.Kind.HASH_MISMATCH,
                        "Downloaded build for branch " + branch + " does not match the published checksum (expected " + release.sha256() + ", got " + actual + ")");
            }

            return new Identity(release.version(), actual);
        }, ServerVersion.parse(release.version()));
    }

    CachedPackage install(String branch, Filler filler) throws PackageException {
        return install(branch, filler, Optional.empty());
    }

    private CachedPackage install(String branch, Filler filler, Optional<ServerVersion> offered) throws PackageException {
        Path dir = branchDir(branch);
        CachedPackage installed;
        try (BranchLock lock = lockBranch(branch)) {
            createBranchDir(dir);
            recoverInterruptedInstall(dir);

            Optional<CachedPackage> present = cached(branch);
            if (holdsSomethingNewerThan(present, offered)) {
                installed = present.get();
                sweepQuietly(dir);
            } else {
                fill(branch, dir, filler, present);

                installed = cached(branch).orElseThrow(() -> new PackageException(PackageException.Kind.CACHE_ERROR,
                        "The installed build for branch " + branch + " disappeared from " + dir));
            }
        }

        evictLeastRecentlyUsed(branch);
        return installed;
    }

    private void sweepQuietly(Path dir) {
        try {
            deleteTemporaryFiles(dir);
        } catch (PackageException ignored) {
        }
    }

    private void fill(String branch, Path dir, Filler filler, Optional<CachedPackage> present) throws PackageException {
        deleteTemporaryFiles(dir);

        Path temp = createTemporaryFile(dir);
        Path pending = dir.resolve(PENDING_NAME);
        Path jar = dir.resolve(JAR_NAME);
        Path previous = null;
        try {
            Identity identity = filler.fill(temp);
            Meta meta = new Meta(identity.version(), identity.sha256(), clock.millis(),
                    present.map(CachedPackage::checkStamp).orElse(CheckStamp.NEVER).installed(clock.millis()));
            writeJson(pending, meta);

            previous = setAside(dir, jar);
            swapIn(branch, temp, jar, previous);
            try {
                writeMeta(dir, meta);
            } catch (PackageException e) {
                // metadata is what identifies the jar, so a jar this call cannot describe must not
                // survive it under the previous build's description
                rollBack(previous, jar);
                throw e;
            }
        } finally {
            deleteQuietly(pending);
            deleteQuietly(temp);
            deleteQuietly(previous);
            deleteIfEmpty(dir);
        }
    }

    private void swapIn(String branch, Path temp, Path jar, Path previous) throws PackageException {
        try {
            move(temp, jar);
        } catch (IOException e) {
            rollBack(previous, jar);
            throw new PackageException(PackageException.Kind.CACHE_ERROR,
                    "Cannot install the build for branch " + branch + " as " + jar + ": " + e.getMessage(), e);
        }
    }

    private void rollBack(Path previous, Path jar) {
        if (!restore(previous, jar)) {
            deleteQuietly(jar);
        }
    }

    public IdentifiedBuild identify(Path source) throws PackageException {
        if (!Files.isRegularFile(source)) {
            throw invalidPackage(source + " is not a file");
        }

        return new IdentifiedBuild(source, nxmcVersion(source, source.toString()));
    }

    public CachedPackage importBuild(IdentifiedBuild build) throws PackageException {
        return install(build.branch(), temp -> copyAndDigest(build, temp), Optional.empty());
    }

    public void cleanupStaleTemporaryFiles() {
        for (String branch : branches()) {
            try (BranchLock lock = lockBranch(branch)) {
                recoverInterruptedInstall(branchDir(branch));
                deleteTemporaryFiles(branchDir(branch));
            } catch (PackageException ignored) {
            }
        }
    }

    private void recoverInterruptedInstall(Path dir) throws PackageException {
        Path pending = dir.resolve(PENDING_NAME);
        if (!Files.isRegularFile(pending)) {
            return;
        }

        Meta marker = readJson(pending);
        Path jar = dir.resolve(JAR_NAME);
        if ((marker != null) && (marker.sha256() != null) && Files.isRegularFile(jar) && sha256(jar).equals(marker.sha256())) {
            writeMeta(dir, promoted(marker, readMeta(dir)));
        }
        deleteQuietly(pending);
    }

    public Optional<ReleaseManifest.Release> updateAvailable(CachedPackage cached, ReleaseManifest manifest) throws ManifestException {
        Optional<ReleaseManifest.Release> release = manifest.release(cached.branch());
        if (release.isEmpty()) {
            return Optional.empty();
        }

        // ReleaseManifest refuses entries whose version cannot be parsed, so this always has a value
        ServerVersion published = ServerVersion.parse(release.get().version()).orElseThrow();
        return published.isNewerPatchThan(cached.version()) ? release : Optional.empty();
    }

    public boolean delete(String branch) throws PackageException {
        Path dir = branchDir(branch);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        try (BranchLock lock = lockBranch(branch)) {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }

            deleteContents(dir);
            try {
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                throw new PackageException(PackageException.Kind.CACHE_ERROR,
                        "Cannot remove cache directory " + dir + ": " + e.getMessage(), e);
            }
        }
        return true;
    }

    public void clearCache() throws PackageException {
        PackageException failure = null;
        for (String branch : cacheEntries()) {
            try {
                delete(branch);
            } catch (PackageException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    List<String> branches() {
        return cacheEntries().stream().filter(name -> isBranch(root.resolve(name))).toList();
    }

    public List<String> cacheEntries() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(root)) {
            return entries.map(p -> p.getFileName().toString()).filter(name -> BRANCH_KEY.matcher(name).matches()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    Path branchDir(String branch) {
        if ((branch == null) || !BRANCH_KEY.matcher(branch).matches()) {
            throw new IllegalArgumentException("Invalid branch key: " + branch);
        }
        return root.resolve(branch);
    }

    Optional<Pin> pin(String branch) throws PackageException {
        return pin(branch, PIN_ATTEMPTS, PIN_RETRY_MILLIS);
    }

    Optional<Pin> pin(String branch, int attempts, long retryMillis) throws PackageException {
        for (int attempt = 1; ; attempt++) {
            try {
                return Optional.of(lock(branch, true));
            } catch (PackageException e) {
                if (e.kind() != PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER) {
                    throw e;
                }
                if (!sharedLocksSupported()) {
                    return Optional.empty();
                }
                if (attempt >= attempts) {
                    throw e;
                }
            }

            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw busy(branch);
            }
        }
    }

    boolean sharedLocksSupported() {
        Boolean known = sharedLocks;
        if (known != null) {
            return known;
        }

        synchronized (SHARED_LOCK_PROBE_MONITOR) {
            if (sharedLocks == null) {
                sharedLocks = probeSharedLocks();
            }
            return sharedLocks;
        }
    }

    private boolean probeSharedLocks() {
        Path probe = locks.resolve(SHARED_LOCK_PROBE + LOCK_SUFFIX);
        try {
            Files.createDirectories(locks);
            try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, true);
                if (lock == null) {
                    return false;
                }

                try {
                    return lock.isShared();
                } finally {
                    lock.release();
                }
            }
        } catch (IOException | OverlappingFileLockException e) {
            return false;
        }
    }

    BranchLock lockBranch(String branch) throws PackageException {
        return lock(branch, false);
    }

    private BranchLock lock(String branch, boolean shared) throws PackageException {
        Path dir = branchDir(branch);
        Path lockFile = locks.resolve(dir.getFileName() + LOCK_SUFFIX);
        FileChannel channel = null;
        try {
            Files.createDirectories(locks);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, shared);
            if (lock == null) {
                channel.close();
                throw busy(branch);
            }
            return new BranchLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw busy(branch);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new PackageException(PackageException.Kind.CACHE_ERROR,
                    "Cannot lock cache directory " + dir + ": " + e.getMessage(), e);
        }
    }

    void evictLeastRecentlyUsed(String keep) {
        List<String> candidates = new ArrayList<>(branches());
        candidates.remove(keep);
        candidates.sort(Comparator.comparingLong(this::lastUsed));

        int excess = branches().size() - MAX_CACHED_BRANCHES;
        for (int i = 0; (excess > 0) && (i < candidates.size()); i++) {
            try {
                delete(candidates.get(i));
                excess--;
            } catch (PackageException ignored) {
            }
        }
    }

    private long lastUsed(String branch) {
        Meta meta = readMeta(branchDir(branch));
        return (meta != null) ? meta.lastUsed() : 0L;
    }

    private void streamTo(ReleaseManifest.Release release, Path target, ProgressListener progress,
                          CancelToken cancel) throws PackageException {
        URI url = URI.create(release.url());
        HttpRequest request = HttpRequest.newBuilder(url).header("User-Agent", ManifestClient.USER_AGENT).GET().build();

        HttpResponse<InputStream> response = send(request, url, cancel);

        InputStream body = response.body();
        ResponseDeadline deadline = new ResponseDeadline(body, stallTimeout, cancel);
        long done = 0;
        try (InputStream in = body; OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            acceptResponse(url, response, cancel);

            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) > 0) {
                deadline.progress();
                if (cancel.cancelled()) {
                    throw cancelled(url);
                }
                out.write(buffer, 0, count);
                done += count;
                if (done > release.size()) {
                    throw downloadFailed(url, "server sent more than the published " + release.size() + " bytes", null);
                }
                progress.onProgress(done, release.size());
            }

            if (cancel.cancelled() && (done < release.size())) {
                throw cancelled(url);
            }
            if (deadline.expired()) {
                throw stalled(url);
            }
        } catch (IOException e) {
            if (done < release.size()) {
                throw incomplete(url, cancel, deadline, e);
            }
        } finally {
            deadline.close();
        }
    }

    private void acceptResponse(URI url, HttpResponse<InputStream> response, CancelToken cancel) throws PackageException {
        if (response.statusCode() != 200) {
            throw downloadFailed(url, "server returned HTTP " + response.statusCode(), null);
        }

        if (!sameOrigin(url, response.uri())) {
            throw downloadFailed(url, "redirected to " + response.uri() + ", which is not the published download " +
                    "origin", null);
        }

        if (cancel.cancelled()) {
            throw cancelled(url);
        }
    }

    private PackageException incomplete(URI url, CancelToken cancel, ResponseDeadline deadline, IOException cause) {
        if (cancel.cancelled()) {
            return cancelled(url);
        }
        if (deadline.expired()) {
            return stalled(url);
        }
        return downloadFailed(url, cause.getMessage(), cause);
    }

    private HttpResponse<InputStream> send(HttpRequest request, URI url, CancelToken cancel) throws PackageException {
        CompletableFuture<HttpResponse<InputStream>> exchange = httpClient.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
        long deadline = System.nanoTime() + headerTimeout.toNanos();
        while (true) {
            if (cancel.cancelled()) {
                abandon(exchange);
                throw cancelled(url);
            }
            try {
                return exchange.get(CANCEL_POLL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // no headers yet: this poll step is not the limit, the header deadline is
                if (System.nanoTime() - deadline >= 0) {
                    abandon(exchange);
                    throw downloadFailed(url,
                            "server sent no response headers within " + headerTimeout.toSeconds() + " seconds", null);
                }
            } catch (ExecutionException e) {
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                throw downloadFailed(url, (cause.getMessage() != null) ? cause.getMessage() : cause.toString(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                abandon(exchange);
                throw downloadFailed(url, "interrupted", e);
            }
        }
    }

    private PackageException stalled(URI url) {
        return downloadFailed(url, "server sent nothing for " + stallTimeout.toSeconds() + " seconds", null);
    }

    private Meta readMeta(Path dir) {
        return readJson(dir.resolve(META_NAME));
    }

    void writeMeta(Path dir, Meta meta) throws PackageException {
        writeJson(dir.resolve(META_NAME), meta);
    }

    private void writeJson(Path file, Meta meta) throws PackageException {
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), META_TEMP_SUFFIX);
            MAPPER.writeValue(temp.toFile(), meta);
            move(temp, file);
        } catch (IOException | JacksonException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR,
                    "Cannot write " + file + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private boolean restore(Path previous, Path jar) {
        if (previous == null) {
            return false;
        }

        try {
            move(previous, jar);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Path createTemporaryFile(Path dir) throws PackageException {
        try {
            return Files.createTempFile(dir, PART_PREFIX, "");
        } catch (IOException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR, "Cannot create temporary file in " + dir +
                    ": " + e.getMessage(), e);
        }
    }

    private void deleteTemporaryFiles(Path dir) throws PackageException {
        sweepBranch(dir, PackageManager::isTemporary, "Cannot clean up temporary files in ");
    }

    private void deleteContents(Path dir) throws PackageException {
        sweepBranch(dir, name -> true, "Cannot clear cache directory ");
    }

    private void sweepBranch(Path dir, Predicate<String> doomed, String failure) throws PackageException {
        try (DirectoryStream<Path> parent = Files.newDirectoryStream(root)) {
            if (parent instanceof SecureDirectoryStream<Path> secure) {
                try (DirectoryStream<Path> branch = secure.newDirectoryStream(dir.getFileName(),
                        LinkOption.NOFOLLOW_LINKS)) {
                    deleteEntries(branch, dir, doomed);
                } catch (IOException e) {
                    if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                        throw e;
                    }
                }
            } else if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> branch = Files.newDirectoryStream(dir)) {
                    deleteEntries(branch, dir, doomed);
                }
            }
        } catch (NoSuchFileException ignored) {
        } catch (IOException e) {
            throw new PackageException(PackageException.Kind.CACHE_ERROR, failure + dir + ": " + e.getMessage(), e);
        }
    }

    private void deleteEntries(DirectoryStream<Path> branch, Path dir, Predicate<String> doomed) throws IOException {
        List<Path> names = new ArrayList<>();
        for (Path entry : branch) {
            Path name = entry.getFileName();
            if (doomed.test(name.toString())) {
                names.add(name);
            }
        }

        names.sort(Comparator.comparingInt(name -> JAR_NAME.equals(name.getFileName().toString()) ? 0 : 1));

        for (Path name : names)
            deleteEntry(branch, dir, name);
    }

    void deleteEntry(DirectoryStream<Path> stream, Path dir, Path name) throws IOException {
        if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
            Files.deleteIfExists(dir.resolve(name));
            return;
        }

        try {
            secure.deleteFile(name);
        } catch (NoSuchFileException ignored) {
        } catch (FileSystemException e) {
            secure.deleteDirectory(name);
        }
    }

    void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    @FunctionalInterface
    interface Filler {
        Identity fill(Path temp) throws PackageException;
    }

    interface Pin extends AutoCloseable {
        @Override
        void close();
    }

    record Identity(String version, String sha256) {
    }

    public record IdentifiedBuild(Path source, ServerVersion version) {
        public String branch() {
            return version.branchKey();
        }
    }

    public record CachedPackage(String branch, Path jar, ServerVersion version, String sha256, Instant lastUsed,
                                long size, CheckStamp checkStamp) {
        public Optional<Instant> lastChecked() {
            return checkStamp.checkedAt();
        }

        public boolean updateArmed() {
            return checkStamp.armed();
        }
    }

    static final class BranchLock implements Pin {
        private final FileChannel channel;
        private final FileLock lock;
        private boolean released;

        BranchLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }

            released = true;
            try {
                lock.release();
            } catch (IOException ignored) {
            }
            closeQuietly(channel);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(String version, String sha256, long lastUsed, CheckStamp lastChecked) {
        Meta {
            lastChecked = (lastChecked != null) ? lastChecked : CheckStamp.NEVER;
        }

        Meta stamped(CheckStamp stamp) {
            return new Meta(version, sha256, lastUsed, stamp);
        }
    }
}
