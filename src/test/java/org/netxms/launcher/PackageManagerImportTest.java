package org.netxms.launcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

class PackageManagerImportTest {
    @TempDir
    Path dataDir;

    @TempDir
    Path sourceDir;

    private TestClock clock;
    private PackageManager manager;

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        manager = newManager();
    }

    private PackageManager newManager() {
        return new PackageManager(dataDir, clock, null);
    }

    @Test
    void identifiesAStandaloneBuild() throws Exception {
        PackageManager.IdentifiedBuild build = manager.identify(standaloneJar("nxmc-standalone.jar", "6.2.1"));

        assertEquals("6.2.1", build.version().full());
        assertEquals("6.2", build.branch());
    }

    @Test
    void identifiesEveryVersionShapeAJarMayName() throws Exception {
        PackageManager.IdentifiedBuild twoComponents = manager.identify(standaloneJar("two.jar", "6.2"));
        assertEquals("6.2", twoComponents.version().full());
        assertEquals("6.2", twoComponents.branch());

        PackageManager.IdentifiedBuild qualified = manager.identify(standaloneJar("qualified.jar", "6.2.1-SNAPSHOT"));
        assertEquals("6.2.1-SNAPSHOT", qualified.version().full());
        assertEquals(1, qualified.version().patch());
        assertEquals("6.2", qualified.branch());
    }

    @Test
    void refusesAFileThatIsNotThere() {
        assertRefused(sourceDir.resolve("nothing-here.jar"));
    }

    @Test
    void refusesADirectory() throws Exception {
        Path dir = Files.createDirectory(sourceDir.resolve("nxmc.jar"));
        assertRefused(dir);
    }

    @Test
    void refusesSomethingThatIsNotAZip() throws Exception {
        Path text = Files.writeString(sourceDir.resolve("readme.jar"), "this is not a jar, it is a note about one");
        assertRefused(text);
    }

    @Test
    void refusesAJarWithNoManifest() throws Exception {
        Path jar = sourceDir.resolve("no-manifest.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("org/netxms/nxmc/BootstrapLoader.class"));
            out.write("not really a class".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertRefused(jar);
    }

    @Test
    void refusesAStandaloneJarThatDoesNotNameItsVersion() throws Exception {
        assertRefused(jar("no-version.jar", PackageManager.NXMC_MAIN_CLASS, null));
    }

    @Test
    void refusesAVersionNoBranchCanBeDerivedFrom() throws Exception {
        assertRefused(jar("unparseable.jar", PackageManager.NXMC_MAIN_CLASS, "latest"));
    }

    @Test
    void refusesABuildBelowTheVersionFloor() throws Exception {
        Path jar = standaloneJar("nxmc-4.5.7.jar", "4.5.7");

        PackageException e = assertThrows(PackageException.class, () -> manager.identify(jar));
        assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind());
        assertTrue(e.getMessage().contains("4.5.7"), e.getMessage());
        assertTrue(e.getMessage().contains("5.0.0"), e.getMessage());
    }

    @Test
    void acceptsTheOldestBuildTheLauncherCanHandASessionTo() throws Exception {
        assertEquals("5.0.0", manager.identify(standaloneJar("nxmc-5.0.0.jar", "5.0.0")).version().full());
    }

    @Test
    void refusesThePlainNxmcJar() throws Exception {
        Path jar = jar("nxmc.jar", "org.netxms.nxmc.Startup", null);

        PackageException e = assertThrows(PackageException.class, () -> manager.identify(jar));
        assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind());
        assertTrue(e.getMessage().contains("org.netxms.nxmc.Startup"), e.getMessage());
        assertTrue(e.getMessage().contains(PackageManager.NXMC_MAIN_CLASS), e.getMessage());
    }

    @Test
    void identifyingTouchesNeitherTheCacheNorItsLocks() throws Exception {
        Path source = standaloneJar("nxmc-standalone.jar", "6.2.1");
        manager.identify(source);
        assertThrows(PackageException.class, () -> manager.identify(jar("wrong.jar", "org.netxms.nxmc.Startup",
                "6.2" + ".1")));

        assertFalse(Files.exists(dataDir.resolve(PackageManager.VERSIONS_DIR)),
                "the branch is only known once the " + "file is read, so nothing may be created before the caller " + "has" + " asked about it");

        try (PackageManager.BranchLock lock = manager.lockBranch("6.2")) {
            assertTrue(Files.isRegularFile(dataDir.resolve(PackageManager.VERSIONS_DIR).resolve(PackageManager.LOCKS_DIR).resolve("6.2" + PackageManager.LOCK_SUFFIX)));
        }
    }

    @Test
    void importInstallsTheFileAsACachedBuild() throws Exception {
        Path source = standaloneJar("nxmc-standalone.jar", "6.2.1");

        PackageManager.CachedPackage installed = manager.importBuild(manager.identify(source));

        assertEquals("6.2", installed.branch());
        assertEquals("6.2.1", installed.version().full());
        assertEquals(sha256(source), installed.sha256(), "the recorded hash is the hash of the bytes that were copied");
        assertEquals(Files.size(source), installed.size());
        assertEquals(clock.instant().toEpochMilli(), installed.lastUsed().toEpochMilli());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(installed.jar()));

        PackageManager.CachedPackage cached = manager.cached("6.2").orElseThrow();
        assertEquals("6.2.1", cached.version().full());
        assertEquals(sha256(source), cached.sha256());
        assertEquals(dataDir.resolve(PackageManager.VERSIONS_DIR).resolve("6.2").resolve(PackageManager.JAR_NAME),
                cached.jar());
        String meta =
                Files.readString(dataDir.resolve(PackageManager.VERSIONS_DIR).resolve("6.2").resolve(PackageManager.META_NAME));
        assertTrue(meta.contains("6.2.1"), meta);
        assertTrue(meta.contains(sha256(source)), meta);
        assertTrue(temporaryFiles("6.2").isEmpty(), temporaryFiles("6.2").toString());
    }

    @Test
    void importReplacesACachedBuildEvenWithAnOlderOne() throws Exception {
        manager.importBuild(manager.identify(standaloneJar("newer.jar", "6.2.1")));

        Path older = standaloneJar("older.jar", "6.2.0");
        PackageManager.CachedPackage installed = manager.importBuild(manager.identify(older));

        assertEquals("6.2.0", installed.version().full());
        assertEquals(sha256(older), installed.sha256());
        PackageManager.CachedPackage cached = manager.cached("6.2").orElseThrow();
        assertEquals("6.2.0", cached.version().full());
        assertEquals(sha256(older), cached.sha256(), "the metadata must describe the jar that is now in place");
        assertArrayEquals(Files.readAllBytes(older), Files.readAllBytes(cached.jar()));
        assertTrue(temporaryFiles("6.2").isEmpty(), temporaryFiles("6.2").toString());
    }

    @Test
    void theRecordedHashIsOfTheBytesThatLandedNotTheOnesIdentifyRead() throws Exception {
        Path source = jar("brought.jar", PackageManager.NXMC_MAIN_CLASS, "6.2.1", "the bytes the user was asked about");
        PackageManager.IdentifiedBuild identified = manager.identify(source);
        String identifiedHash = sha256(source);

        Path swapped = jar("brought.jar", PackageManager.NXMC_MAIN_CLASS, "6.2.1",
                "a different build of the very " + "same version");
        assertNotEquals(identifiedHash, sha256(swapped), "the swap has to change the bytes for this test to say " +
                "anything");

        PackageManager.CachedPackage installed = manager.importBuild(identified);

        assertEquals("6.2.1", installed.version().full());
        assertEquals(sha256(swapped), installed.sha256(), "the metadata must describe the jar that is now in place");
        assertArrayEquals(Files.readAllBytes(swapped), Files.readAllBytes(installed.jar()));
        assertEquals(sha256(installed.jar()), manager.cached("6.2").orElseThrow().sha256());
    }

    @Test
    void aSourceThatChangedWhileTheUserWasAskedIsRefused() throws Exception {
        Path kept = standaloneJar("kept.jar", "6.2.1");
        manager.importBuild(manager.identify(kept));

        Path source = standaloneJar("brought.jar", "6.2.3");
        PackageManager.IdentifiedBuild identified = manager.identify(source);

        jar("brought.jar", PackageManager.NXMC_MAIN_CLASS, "6.2.5");
        PackageException e = assertThrows(PackageException.class, () -> manager.importBuild(identified));
        assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains("6.2.5") && e.getMessage().contains("6.2.3"), e.getMessage());

        jar("brought.jar", "org.netxms.nxmc.Startup", "6.2.3");
        assertEquals(PackageException.Kind.INVALID_PACKAGE, assertThrows(PackageException.class,
                () -> manager.importBuild(identified)).kind());
        Files.writeString(source, "half a jar, still being written");
        assertEquals(PackageException.Kind.INVALID_PACKAGE, assertThrows(PackageException.class,
                () -> manager.importBuild(identified)).kind());

        PackageManager.CachedPackage cached = manager.cached("6.2").orElseThrow();
        assertEquals("6.2.1", cached.version().full(), "a refused import must leave the branch as it found it");
        assertEquals(sha256(kept), cached.sha256());
        assertArrayEquals(Files.readAllBytes(kept), Files.readAllBytes(cached.jar()));
        assertTrue(temporaryFiles("6.2").isEmpty(), temporaryFiles("6.2").toString());
    }

    @Test
    void aFailedMetadataWriteRestoresThePreviouslyCachedBuild() throws Exception {
        Path kept = standaloneJar("kept.jar", "6.2.1");
        manager.importBuild(manager.identify(kept));

        PackageManager failing = new PackageManager(dataDir, clock, null) {
            @Override
            void writeMeta(Path dir, PackageManager.Meta meta) throws PackageException {
                throw new PackageException(PackageException.Kind.CACHE_ERROR, "metadata cannot be written");
            }
        };

        PackageManager.IdentifiedBuild identified = manager.identify(standaloneJar("brought.jar", "6.2.3"));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class,
                () -> failing.importBuild(identified)).kind());

        PackageManager.CachedPackage cached = manager.cached("6.2").orElseThrow();
        assertEquals("6.2.1", cached.version().full());
        assertEquals(sha256(kept), cached.sha256());
        assertArrayEquals(Files.readAllBytes(kept), Files.readAllBytes(cached.jar()));
        assertTrue(temporaryFiles("6.2").isEmpty(), "the set-aside copy must not survive as a leftover");
    }

    @Test
    void aBranchAnotherInstanceHoldsIsBusyAndTheSourceIsLeftAlone() throws Exception {
        Path source = standaloneJar("nxmc-standalone.jar", "6.2.1");
        byte[] bytes = Files.readAllBytes(source);
        PackageManager.IdentifiedBuild identified = manager.identify(source);

        PackageManager other = newManager();
        try (PackageManager.BranchLock held = manager.lockBranch("6.2")) {
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.importBuild(identified)).kind());
        }

        assertArrayEquals(bytes, Files.readAllBytes(source), "the file the user brought is only ever read");
        assertTrue(other.cached("6.2").isEmpty());
        assertEquals("6.2.1", other.importBuild(identified).version().full(),
                "the branch is installable once the " + "lock is gone");
    }

    @Test
    void anImportedBuildIsStampedAndHeldLikeAnyOther() throws Exception {
        Path source = standaloneJar("nxmc-standalone.jar", "6.2.1");
        manager.importBuild(manager.identify(source));

        clock.advance(Duration.ofMinutes(5));
        manager.markUsed("6.2");
        PackageManager.CachedPackage stamped = manager.cached("6.2").orElseThrow();
        assertEquals(clock.instant().toEpochMilli(), stamped.lastUsed().toEpochMilli());
        assertEquals("6.2.1", stamped.version().full(), "a stamp says when, not what");
        assertEquals(sha256(source), stamped.sha256());

        PackageManager other = newManager();
        PackageManager.IdentifiedBuild replacement = manager.identify(standaloneJar("replacement.jar", "6.2.3"));
        try (PackageManager.Pin held = manager.pin("6.2").orElseThrow()) {
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.delete("6.2")).kind());
            assertEquals(PackageException.Kind.LOCKED_BY_ANOTHER_LAUNCHER, assertThrows(PackageException.class,
                    () -> other.importBuild(replacement)).kind());
        }

        assertEquals("6.2.1", manager.cached("6.2").orElseThrow().version().full());
    }

    @Test
    void anImportLeavesAnArmedBranchArmed() throws Exception {
        manager.importBuild(manager.identify(standaloneJar("nxmc-standalone.jar", "6.2.1")));
        manager.armUpdateCheck("6.2");
        CheckStamp armed = manager.cached("6.2").orElseThrow().checkStamp();

        clock.advance(Duration.ofMinutes(5));
        PackageManager.CachedPackage imported = manager.importBuild(manager.identify(standaloneJar("added.jar",
                "6.2" + ".2")));

        assertEquals("6.2.2", imported.version().full());
        assertTrue(imported.updateArmed(), "the build the user added is not the one the check found");
        assertEquals(armed, manager.cached("6.2").orElseThrow().checkStamp());
    }

    @Test
    void metadataRecordsNothingAboutWhereABuildCameFrom() throws Exception {
        manager.importBuild(manager.identify(standaloneJar("nxmc-standalone.jar", "6.2.1")));

        List<String> components =
                Stream.of(PackageManager.Meta.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("version", "sha256", "lastUsed", "lastChecked"), components, "an origin marker was " +
                "weighed and declined");

        JsonNode meta =
                new ObjectMapper().readTree(dataDir.resolve(PackageManager.VERSIONS_DIR).resolve("6.2").resolve(PackageManager.META_NAME).toFile());
        assertEquals(components, List.copyOf(meta.propertyNames()));
    }

    @Test
    void importEvictsTheLeastRecentlyUsedBranch() throws Exception {
        importBuild("5.0.7");
        clock.advance(Duration.ofMinutes(10));
        importBuild("5.1.2");
        clock.advance(Duration.ofMinutes(10));
        importBuild("5.2.3");
        assertEquals(List.of("5.0", "5.1", "5.2"), manager.branches());

        clock.advance(Duration.ofMinutes(10));
        importBuild("6.0.1");

        assertEquals(List.of("5.1", "5.2", "6.0"), manager.branches(),
                "eviction counts an imported build like any " + "other");
        assertTrue(manager.cached("5.0").isEmpty());
        assertEquals(PackageManager.MAX_CACHED_BRANCHES, manager.cachedPackages().size());
    }

    @Test
    void aBranchPathThatIsNotADirectoryRefusesTheImport() throws Exception {
        Path versions = Files.createDirectories(dataDir.resolve(PackageManager.VERSIONS_DIR));
        Files.writeString(versions.resolve("6.2"), "not a branch directory");

        PackageManager.IdentifiedBuild identified = manager.identify(standaloneJar("nxmc-standalone.jar", "6.2.1"));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class,
                () -> manager.importBuild(identified)).kind());
        assertEquals("not a branch directory", Files.readString(versions.resolve("6.2")));
    }

    @Test
    void aBranchPathThatIsASymbolicLinkRefusesTheImport() throws Exception {
        Path outside = Files.createDirectories(dataDir.resolve("elsewhere"));
        Path link = Files.createDirectories(dataDir.resolve(PackageManager.VERSIONS_DIR)).resolve("6.2");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            abort("no symbolic links on this platform, so there is nothing to protect against");
        }

        PackageManager.IdentifiedBuild identified = manager.identify(standaloneJar("nxmc-standalone.jar", "6.2.1"));
        assertEquals(PackageException.Kind.CACHE_ERROR, assertThrows(PackageException.class,
                () -> manager.importBuild(identified)).kind());

        assertTrue(Files.isSymbolicLink(link));
        try (Stream<Path> entries = Files.list(outside)) {
            assertTrue(entries.findAny().isEmpty(), "the import wrote through the link");
        }
    }

    @Test
    void aSourceThatCannotBeReadIsACacheErrorAndCostsTheBranchNothing() throws Exception {
        Path source = standaloneJar("nxmc-standalone.jar", "6.2.1");
        PackageManager.IdentifiedBuild identified = manager.identify(source);
        Files.delete(source);

        PackageException e = assertThrows(PackageException.class, () -> manager.importBuild(identified));
        assertEquals(PackageException.Kind.CACHE_ERROR, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains(source.toString()), e.getMessage());
        assertTrue(manager.cached("6.2").isEmpty());
        assertFalse(Files.exists(dataDir.resolve(PackageManager.VERSIONS_DIR).resolve("6.2"),
                LinkOption.NOFOLLOW_LINKS), "a branch nothing was installed into must be given back");
    }

    @Test
    void noRejectedFileLeavesATraceInTheCache() throws Exception {
        List<Path> refused = List.of(sourceDir.resolve("nothing-here.jar"), Files.createDirectory(sourceDir.resolve(
                        "a-directory.jar")), Files.writeString(sourceDir.resolve("prose.jar"),
                        "a note about a jar, not a " + "jar"), jar("no-version.jar", PackageManager.NXMC_MAIN_CLASS, null),
                jar("unparseable.jar", PackageManager.NXMC_MAIN_CLASS, "latest"), jar("nxmc.jar",
                        "org.netxms.nxmc" + ".Startup", "6.2.1"), jar("anonymous.jar", null, "6.2.1"));

        for (Path source : refused)
            assertRefused(source);

        assertFalse(Files.exists(dataDir.resolve(PackageManager.VERSIONS_DIR)), "not even the cache root may be " +
                "created");
        assertEquals(List.of(), manager.cacheEntries());
    }

    @Test
    void anImportRefusedOnAFreshBranchGivesTheBranchDirectoryBack() throws Exception {
        PackageManager.IdentifiedBuild identified = manager.identify(standaloneJar("brought.jar", "6.2.1"));
        jar("brought.jar", "org.netxms.nxmc.Startup", "6.2.1");

        assertEquals(PackageException.Kind.INVALID_PACKAGE, assertThrows(PackageException.class,
                () -> manager.importBuild(identified)).kind());

        assertFalse(Files.exists(dataDir.resolve(PackageManager.VERSIONS_DIR).resolve("6.2"),
                LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of(), manager.cacheEntries());
        assertTrue(manager.cached("6.2").isEmpty());
    }

    private void importBuild(String version) throws Exception {
        manager.importBuild(manager.identify(standaloneJar("nxmc-" + version + ".jar", version)));
    }

    private List<Path> temporaryFiles(String branch) throws IOException {
        Path dir = dataDir.resolve(PackageManager.VERSIONS_DIR).resolve(branch);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(PackageManager.PART_PREFIX) || name.endsWith(PackageManager.META_TEMP_SUFFIX) || name.equals(PackageManager.PENDING_NAME);
            }).toList();
        }
    }

    private void assertRefused(Path source) {
        PackageException e = assertThrows(PackageException.class, () -> manager.identify(source));
        assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains(source.toString()), e.getMessage());
    }

    private Path standaloneJar(String name, String version) throws IOException {
        return jar(name, PackageManager.NXMC_MAIN_CLASS, version);
    }

    private Path jar(String name, String mainClass, String version) throws IOException {
        return jar(name, mainClass, version, "not really a class");
    }

    private Path jar(String name, String mainClass, String version, String payload) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        if (version != null) {
            attributes.putValue(PackageManager.PACKAGE_VERSION_ATTRIBUTE, version);
        }

        Path file = sourceDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("org/netxms/nxmc/BootstrapLoader.class"));
            jar.write(payload.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-07-25T10:00:00Z");

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
