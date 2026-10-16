package org.netxms.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BootstrapTest {
    private static final String SWT_LINUX = "BOOT-INF/swt/org.eclipse.swt.gtk.linux.x86_64-3.132.0.jar";
    private static final String SWT_MACOS = "BOOT-INF/swt/org.eclipse.swt.cocoa.macosx.aarch64-3.132.0.jar";

    @TempDir
    Path workDir;

    @TempDir
    Path tempDir;

    private URLClassLoader isolated;

    @AfterEach
    void closeIsolatedBootstrap() throws IOException {
        if (isolated != null) {
            isolated.close();
            isolated = null;
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Linux,        amd64,   gtk.linux.x86_64",
            "Linux,        x86_64,  gtk.linux.x86_64",
            "Linux,        " + "aarch64, gtk.linux.aarch64",
            "linux,        amd64,   gtk.linux.x86_64",
            "LINUX,        AARCH64, gtk" + ".linux.aarch64",
            "Mac OS X,     x86_64,  cocoa.macosx.x86_64",
            "Mac OS X,     amd64,   cocoa.macosx" + ".x86_64",
            "Mac OS X,     aarch64, cocoa.macosx.aarch64",
            "mac os x,     aarch64, cocoa.macosx.aarch64",
            "MAC OS X,     X86_64,  cocoa.macosx.x86_64",
            "macOS,        aarch64, cocoa.macosx.aarch64",
            "Windows 11," + "          amd64,   win32.win32.x86_64",
            "Windows Server 2022, x86_64,  win32.win32.x86_64",
            "Windows 10," + "          aarch64, win32.win32.aarch64",
            "windows 11,          amd64,   win32.win32.x86_64",
            "WINDOWS " + "11,          AARCH64, win32.win32.aarch64"
    })
    void mapsSupportedPlatformsToFragments(String osName, String osArch, String variant) {
        assertEquals(Optional.of(variant), Bootstrap.swtVariant(osName, osArch));
    }

    @ParameterizedTest
    @CsvSource({
            "Windows 10,          x86",
            "Linux,               riscv64",
            "Linux,               arm",
            "Linux,      " + "         ppc64le",
            "Mac OS X,            ppc",
            "SunOS,               sparcv9",
            "FreeBSD,    " + "       " + "  amd64",
            "AIX,                 ppc64"
    })
    void hasNoFragmentForUnsupportedPlatforms(String osName, String osArch) {
        assertEquals(Optional.empty(), Bootstrap.swtVariant(osName, osArch));
    }

    @Test
    void readsEmptyPlatformPropertiesAsUnsupported() {
        assertEquals(Optional.empty(), Bootstrap.swtVariant("", ""));
        assertEquals(Optional.empty(), Bootstrap.swtVariant("Linux", ""));
        assertEquals(Optional.empty(), Bootstrap.swtVariant("", "amd64"));
    }

    @Test
    void extractsEveryCoreJarAndOnlyTheFragmentForTheVariant() throws Exception {
        Path fat = fatJar(Map.of("BOOT-INF/core/nxmc-launcher-1.0.0.jar", "core-launcher", "BOOT-INF/core/netxms" +
                "-client-5.2.3.jar", "core-client", SWT_LINUX, "swt-linux-x86_64", SWT_MACOS, "swt-macos-aarch64"));

        List<URL> classpath = Bootstrap.extractClasspath(fat, "cocoa.macosx.aarch64", tempDir);

        assertEquals(List.of("core-client", "core-launcher", "swt-macos-aarch64"), contents(classpath));
        assertEquals(3, extracted().size());
    }

    @Test
    void givesEachRunItsOwnCopiesSoConcurrentLaunchersDoNotOverwriteEachOther() throws Exception {
        Path fat = fatJar(Map.of("BOOT-INF/core/nxmc-launcher-1.0.0.jar", "core-launcher", SWT_LINUX, "swt-linux" +
                "-x86_64"));

        List<URL> first = Bootstrap.extractClasspath(fat, "gtk.linux.x86_64", tempDir);
        List<URL> second = Bootstrap.extractClasspath(fat, "gtk.linux.x86_64", tempDir);

        assertTrue(Collections.disjoint(first, second), first + " vs " + second);
        assertEquals(contents(first), contents(second));
        assertEquals(4, extracted().size());
        assertEquals(2, scratchDirs().size());
    }

    @Test
    void extractsIntoAPrivateDirectoryRatherThanTheSharedTempRoot() throws Exception {
        Path fat = fatJar(Map.of("BOOT-INF/core/nxmc-launcher-1.0.0.jar", "core-launcher", SWT_LINUX, "swt-linux" +
                "-x86_64"));

        List<URL> classpath = Bootstrap.extractClasspath(fat, "gtk.linux.x86_64", tempDir);

        List<Path> scratch = scratchDirs();
        assertEquals(1, scratch.size(), scratch.toString());
        for (URL url : classpath)
            assertEquals(scratch.get(0), Paths.get(url.toURI()).getParent());
    }

    @Test
    void keepsTheScratchDirectoryOutOfReachOfOtherUsers() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class), "POSIX " +
                "permissions required");
        Path fat = fatJar(Map.of(SWT_LINUX, "swt-linux-x86_64"));

        Bootstrap.extractClasspath(fat, "gtk.linux.x86_64", tempDir);

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(scratchDirs().get(0)));
    }

    @Test
    void keepsTheExtractedJarsAtTheModeTheExclusiveCreateGaveThem() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class), "POSIX " +
                "permissions required");
        Path fat = fatJar(Map.of("BOOT-INF/core/nxmc-launcher-1.0.0.jar", "core-launcher", SWT_LINUX, "swt-linux" +
                "-x86_64"));

        List<URL> classpath = Bootstrap.extractClasspath(fat, "gtk.linux.x86_64", tempDir);

        for (URL url : classpath)
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(Paths.get(url.toURI())), url.toString());
    }

    @Test
    void aJarPlantedInTheSharedTempRootCannotServeClassesThroughANestedClassPath() throws Exception {
        Files.copy(launcherJar("planted", "System.out.println(\"planted\");\n"),
                tempDir.resolve("commons-codec-1.18" + ".0.jar"));
        Path fat = fatJarOf(Map.of("BOOT-INF/core/netxms-base-6.2.1.jar", classPathJar("commons-codec-1.18.0.jar"),
                SWT_LINUX, "swt-linux-x86_64".getBytes(StandardCharsets.UTF_8)));

        List<URL> classpath = Bootstrap.extractClasspath(fat, "gtk.linux.x86_64", tempDir);

        try (URLClassLoader loader = new URLClassLoader(classpath.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.netxms.launcher.Launcher"));
        }
    }

    @Test
    void extractsOnlyTheFragmentWhenThereAreNoCoreJars() throws Exception {
        Path fat = fatJar(Map.of(SWT_LINUX, "swt-linux-x86_64"));

        assertEquals(List.of("swt-linux-x86_64"), contents(Bootstrap.extractClasspath(fat, "gtk.linux.x86_64",
                tempDir)));
    }

    @Test
    void refusesAJarWithNoFragmentForTheVariant() throws Exception {
        Path fat = fatJar(Map.of("BOOT-INF/core/nxmc-launcher-1.0.0.jar", "core-launcher", SWT_LINUX, "swt-linux" +
                "-x86_64"));

        IOException e = assertThrows(IOException.class, () -> Bootstrap.extractClasspath(fat, "cocoa.macosx.aarch64",
                tempDir));

        assertTrue(e.getMessage().contains("cocoa.macosx.aarch64"), e.getMessage());
        assertEquals(List.of(), extracted());
    }

    @Test
    void refusesAJarCarryingTwoFragmentsForOneVariant() throws Exception {
        Path fat = fatJar(Map.of("BOOT-INF/core/nxmc-launcher-1.0.0.jar", "core-launcher", SWT_LINUX, "swt-linux" +
                "-x86_64", "BOOT-INF/swt/org.eclipse.swt.gtk.linux.x86_64-3.128.0.jar", "swt-linux-x86_64-stale"));

        IOException e = assertThrows(IOException.class, () -> Bootstrap.extractClasspath(fat, "gtk.linux.x86_64",
                tempDir));

        assertTrue(e.getMessage().contains("3.132.0.jar") && e.getMessage().contains("3.128.0.jar"), e.getMessage());
        assertEquals(List.of(), extracted());
    }

    @Test
    void launchRunsLauncherMainOutOfTheExtractedJar() throws Throwable {
        Path recorded = workDir.resolve("launcher-run.txt");
        Path core = launcherJar("recording", "java.util.List<String> lines = new java.util.ArrayList<>(java.util" +
                ".Arrays.asList(args));\n" + "lines.add(Launcher.class.getProtectionDomain().getCodeSource()" +
                ".getLocation().toString());\n" + "java.nio.file.Files.write(java.nio.file.Paths.get(\"" + JavaSource.pathLiteral(recorded) + "\"), lines);\n");

        launch(List.of(core.toUri().toURL()), "-version", "-x");

        assertEquals(List.of("-version", "-x", core.toUri().toURL().toString()), Files.readAllLines(recorded));
    }

    @Test
    void launchSurfacesALauncherCrashUnwrapped() throws Exception {
        Path core = launcherJar("crashing", "throw new IllegalStateException(\"launcher crashed\");\n");
        List<URL> classpath = List.of(core.toUri().toURL());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> launch(classpath));

        assertEquals("launcher crashed", e.getMessage());
    }

    private void launch(List<URL> classpath, String... args) throws Throwable {
        Method launch = isolatedBootstrap().getDeclaredMethod("launch", List.class, String[].class);
        launch.setAccessible(true);
        try {
            launch.invoke(null, classpath, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Class<?> isolatedBootstrap() throws Exception {
        Path classes = Paths.get(Bootstrap.class.getResource("Bootstrap.class").toURI()).getParent();
        Path jar = workDir.resolve("bootstrap.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(classes, "Bootstrap*.class")) {
                for (Path file : files) {
                    jarOut.putNextEntry(new JarEntry("org/netxms/launcher/" + file.getFileName()));
                    jarOut.write(Files.readAllBytes(file));
                    jarOut.closeEntry();
                }
            }
        }

        isolated = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        return isolated.loadClass("org.netxms.launcher.Bootstrap");
    }

    private Path launcherJar(String name, String body) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "JDK compiler required to build the fake launcher jar");

        Path buildDir = Files.createDirectories(workDir.resolve(name));
        Path source = buildDir.resolve("Launcher.java");
        Files.writeString(source,
                "package org.netxms.launcher;\n" + "public final class Launcher\n" + "{\n" + "   " + "public static " + "void main(String[] args) throws Exception\n" + "   {\n" + body + "   }\n" + "}\n");
        assertEquals(0, compiler.run(null, null, null, "-d", buildDir.toString(), source.toString()), "fake launcher "
                + "must compile");

        Path jar = buildDir.resolve("nxmc-launcher-1.0.0.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("org/netxms/launcher/Launcher.class"));
            jarOut.write(Files.readAllBytes(buildDir.resolve("org/netxms/launcher/Launcher.class")));
            jarOut.closeEntry();
        }
        return jar;
    }

    private Path fatJar(Map<String, String> entries) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        entries.forEach((name, content) -> bytes.put(name, content.getBytes(StandardCharsets.UTF_8)));
        return fatJarOf(bytes);
    }

    private Path fatJarOf(Map<String, byte[]> entries) throws IOException {
        Path jar = workDir.resolve("nxmc-launcher-standalone.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("org/netxms/launcher/Bootstrap.class"));
            jarOut.write("not a class, and never read".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue());
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    private byte[] classPathJar(String entry) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, entry);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jarOut = new JarOutputStream(bytes, manifest)) {
            jarOut.putNextEntry(new JarEntry("core-base.txt"));
            jarOut.closeEntry();
        }
        return bytes.toByteArray();
    }

    private List<String> contents(List<URL> classpath) throws Exception {
        List<String> contents = new ArrayList<>();
        for (URL url : classpath)
            contents.add(Files.readString(Paths.get(url.toURI())));
        return contents;
    }

    private List<String> extracted() throws IOException {
        try (Stream<Path> files = Files.walk(tempDir)) {
            return files.filter(Files::isRegularFile).map(Path::getFileName).map(Path::toString).sorted().collect(Collectors.toList());
        }
    }

    private List<Path> scratchDirs() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            return entries.filter(Files::isDirectory).sorted().collect(Collectors.toList());
        }
    }
}
