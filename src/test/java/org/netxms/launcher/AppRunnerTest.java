package org.netxms.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AppRunnerTest {
    private static final String TOKEN = "eph-token-12345";

    private static final String ECHO_ARGUMENTS = "__echo-arguments__";

    private static final String HOST_JAVA_EXECUTABLE =
            new AppRunner(Map.of(), AppRunner.HEALTH_WINDOW).javaExecutable();

    @TempDir
    Path workDir;

    private Process started;

    private static Map<String, String> env(String javaHome, String path) {
        Map<String, String> env = new HashMap<>();
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome);
        }
        if (path != null) {
            env.put("PATH", path);
        }
        return env;
    }

    private static AppRunner windowsRunner(Map<String, String> env) {
        return new AppRunner(env, AppRunner.HEALTH_WINDOW, "Windows 11");
    }

    private static Path executableJava(Path binDir) throws IOException {
        return javaExecutable(binDir, HOST_JAVA_EXECUTABLE);
    }

    private static Path javaExecutable(Path binDir, String name) throws IOException {
        Files.createDirectories(binDir);
        Path java = binDir.resolve(name);
        if (posixHost()) {
            Files.writeString(java, "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        } else {
            Files.writeString(java, "");
        }
        return java;
    }

    private static boolean posixHost() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (started == null) {
            return;
        }

        started.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }

    @Test
    void handsOffServerTokenAndAutoFlag() throws Exception {
        Path argsFile = workDir.resolve("args.txt");
        Path jar = fakeNxmc(argsFile, "", 0, 60000);

        started = new AppRunner(System.getenv(), Duration.ofMillis(600)).run(jar, "srv.example.com", 4703, TOKEN);

        assertTrue(started.isAlive());
        assertEquals(List.of("-server=srv.example.com:4703", "-token=" + TOKEN, "-auto"), waitForArgs(argsFile));
    }

    @Test
    void reportsExitCodeAndStderrWhenChildDiesEarly() throws Exception {
        Path argsFile = workDir.resolve("args.txt");
        Path jar = fakeNxmc(argsFile, "java.lang.NoClassDefFoundError: org/eclipse/swt/widgets/Display", 3, 0);

        LaunchFailure failure = assertThrows(LaunchFailure.class, () -> new AppRunner(System.getenv(),
                Duration.ofSeconds(5)).run(jar, "srv.example.com", 4701, TOKEN));

        assertEquals(LaunchFailure.Kind.EARLY_EXIT, failure.kind());
        assertEquals(3, failure.exitCode());
        assertTrue(failure.stderrTail().contains("NoClassDefFoundError"), failure.stderrTail());
    }

    @Test
    void failsWhenCachedJarIsMissing() {
        LaunchFailure failure = assertThrows(LaunchFailure.class, () -> new AppRunner(System.getenv(),
                Duration.ofMillis(200)).run(workDir.resolve("gone.jar"), "srv", 4701, TOKEN));

        assertEquals(LaunchFailure.Kind.SPAWN_FAILED, failure.kind());
        assertEquals(LaunchFailure.NO_EXIT_CODE, failure.exitCode());
        assertEquals("", failure.stderrTail());
    }

    @Test
    void rejectsMissingServerOrToken() throws Exception {
        Path jar = fakeNxmc(workDir.resolve("args.txt"), "", 0, 1000);
        AppRunner runner = new AppRunner(System.getenv(), Duration.ofMillis(200));

        assertThrows(IllegalArgumentException.class, () -> runner.run(jar, " ", 4701, TOKEN));
        assertThrows(IllegalArgumentException.class, () -> runner.run(jar, "srv", 4701, ""));
        assertThrows(NullPointerException.class, () -> runner.run(null, "srv", 4701, TOKEN));
    }

    @Test
    void prefersJavaHomeOverPath() throws Exception {
        Path javaHome = executableJava(workDir.resolve("jdk").resolve("bin"));
        Path onPath = executableJava(workDir.resolve("path"));

        Path resolved = new AppRunner(env(javaHome.getParent().getParent().toString(), onPath.getParent().toString())
                , AppRunner.HEALTH_WINDOW).javaBinary();

        assertEquals(javaHome, resolved);
    }

    @Test
    void fallsBackToPathWhenJavaHomeIsUnusable() throws Exception {
        Path onPath = executableJava(workDir.resolve("path"));
        Path emptyHome = Files.createDirectories(workDir.resolve("empty-home"));
        String searchPath = workDir.resolve("nowhere") + java.io.File.pathSeparator + onPath.getParent();

        assertEquals(onPath, new AppRunner(env(null, searchPath), AppRunner.HEALTH_WINDOW).javaBinary());
        assertEquals(onPath,
                new AppRunner(env(emptyHome.toString(), searchPath), AppRunner.HEALTH_WINDOW).javaBinary());
        assertEquals(onPath, new AppRunner(env("   ", searchPath), AppRunner.HEALTH_WINDOW).javaBinary());
    }

    @Test
    void failsWhenNoJavaBinaryExists() throws Exception {
        Path emptyDir = Files.createDirectories(workDir.resolve("empty"));

        LaunchFailure failure = assertThrows(LaunchFailure.class,
                () -> new AppRunner(env(workDir.resolve("nojdk").toString(), emptyDir.toString()),
                        AppRunner.HEALTH_WINDOW).javaBinary());
        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, failure.kind());

        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, assertThrows(LaunchFailure.class,
                () -> new AppRunner(env(null, null), AppRunner.HEALTH_WINDOW).javaBinary()).kind());
    }

    @Test
    void nonExecutableJavaIsIgnored() throws Exception {
        assumeTrue(posixHost(), "Files.isExecutable reads the ACL on Windows, which grants execute to any new file");

        Path bin = Files.createDirectories(workDir.resolve("path"));
        Files.writeString(bin.resolve(HOST_JAVA_EXECUTABLE), "not executable");

        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, assertThrows(LaunchFailure.class,
                () -> new AppRunner(env(null, bin.toString()), AppRunner.HEALTH_WINDOW).javaBinary()).kind());
    }

    @Test
    void windowsResolvesJavawFromJavaHomeAndPath() throws Exception {
        Path home = workDir.resolve("jdk");
        Path javaw = javaExecutable(home.resolve("bin"), AppRunner.WINDOWS_JAVA_EXECUTABLE);
        Path onPath = javaExecutable(workDir.resolve("path"), AppRunner.WINDOWS_JAVA_EXECUTABLE);

        assertEquals(javaw, windowsRunner(env(home.toString(), onPath.getParent().toString())).javaBinary());
        assertEquals(onPath, windowsRunner(env(null, onPath.getParent().toString())).javaBinary());
    }

    @Test
    void windowsIgnoresAPlainJavaBinary() throws Exception {
        Path home = workDir.resolve("jdk");
        javaExecutable(home.resolve("bin"), AppRunner.POSIX_JAVA_EXECUTABLE);
        Path onPath = javaExecutable(workDir.resolve("path"), AppRunner.POSIX_JAVA_EXECUTABLE);

        LaunchFailure failure = assertThrows(LaunchFailure.class, () -> windowsRunner(env(home.toString(),
                onPath.getParent().toString())).javaBinary());

        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, failure.kind());
        assertTrue(failure.getMessage().contains(AppRunner.WINDOWS_JAVA_EXECUTABLE), failure.getMessage());
    }

    @Test
    void windowsReadsThePathVariableWhateverCaseItIsSpelledIn() throws Exception {
        Path onPath = javaExecutable(workDir.resolve("path"), AppRunner.WINDOWS_JAVA_EXECUTABLE);
        Map<String, String> env = new HashMap<>();
        env.put("Path", onPath.getParent().toString());
        env.put("JavaHome-not-read", workDir.toString());

        assertEquals(onPath, windowsRunner(env).javaBinary());

        Map<String, String> home = new HashMap<>();
        home.put("Java_Home", javaExecutable(workDir.resolve("jdk").resolve("bin"),
                AppRunner.WINDOWS_JAVA_EXECUTABLE).getParent().getParent().toString());
        assertEquals(workDir.resolve("jdk").resolve("bin").resolve(AppRunner.WINDOWS_JAVA_EXECUTABLE),
                windowsRunner(home).javaBinary());
    }

    @Test
    void otherPlatformsReadThePathVariableExactly() throws Exception {
        Path onPath = javaExecutable(workDir.resolve("path"), AppRunner.POSIX_JAVA_EXECUTABLE);
        Map<String, String> env = new HashMap<>();
        env.put("Path", onPath.getParent().toString());

        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, assertThrows(LaunchFailure.class, () -> new AppRunner(env,
                AppRunner.HEALTH_WINDOW, "Linux").javaBinary()).kind());
    }

    @Test
    void anEntryThatIsNotAPathDoesNotEndTheSearch() throws Exception {
        Path onPath = executableJava(workDir.resolve("path"));
        String unusable = "C:\\bad\u0000name";

        assertEquals(onPath, new AppRunner(env(unusable, unusable + java.io.File.pathSeparator + onPath.getParent()),
                AppRunner.HEALTH_WINDOW).javaBinary());
    }

    @Test
    void windowsSplitsThePathOnSemicolons() throws Exception {
        Path onPath = javaExecutable(workDir.resolve("path"), AppRunner.WINDOWS_JAVA_EXECUTABLE);
        Path other = Files.createDirectories(workDir.resolve("other"));

        assertEquals(onPath, windowsRunner(env(null, other + ";" + onPath.getParent())).javaBinary());

        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, assertThrows(LaunchFailure.class,
                () -> windowsRunner(env(null, other + ":" + onPath.getParent())).javaBinary()).kind());
    }

    @Test
    void otherPlatformsKeepLookingForPlainJava() throws Exception {
        Path home = workDir.resolve("jdk");
        Path java = javaExecutable(home.resolve("bin"), AppRunner.POSIX_JAVA_EXECUTABLE);
        javaExecutable(home.resolve("bin"), AppRunner.WINDOWS_JAVA_EXECUTABLE);

        assertEquals(java, new AppRunner(env(home.toString(), null), AppRunner.HEALTH_WINDOW, "Linux").javaBinary());

        Path javawOnly = javaExecutable(workDir.resolve("javaw-only"), AppRunner.WINDOWS_JAVA_EXECUTABLE);
        LaunchFailure failure = assertThrows(LaunchFailure.class, () -> new AppRunner(env(null,
                javawOnly.getParent().toString()), AppRunner.HEALTH_WINDOW, "Mac OS X").javaBinary());

        assertEquals(LaunchFailure.Kind.JAVA_NOT_FOUND, failure.kind());
        assertTrue(failure.getMessage().contains("'" + AppRunner.POSIX_JAVA_EXECUTABLE + "'"), failure.getMessage());
    }

    @Test
    void childOutlivingTheHealthWindowIsATreatedAsSuccessfulHandOff() throws Exception {
        Path jar = fakeNxmc(workDir.resolve("args.txt"), "", 0, 4000);

        started = new AppRunner(System.getenv(), Duration.ofMillis(300)).run(jar, "srv", 4701, TOKEN);

        assertTrue(started.isAlive());
    }

    @Test
    void redactsTheTokenFromWhatTheChildPrints() throws Exception {
        Path jar = fakeNxmc(workDir.resolve("args.txt"), ECHO_ARGUMENTS, 1, 0);

        LaunchFailure failure = assertThrows(LaunchFailure.class, () -> new AppRunner(System.getenv(),
                Duration.ofSeconds(5)).run(jar, "srv.example.com", 4701, TOKEN));

        assertTrue(failure.stderrTail().contains("-server=srv.example.com"), failure.stderrTail());
        assertFalse(failure.stderrTail().contains(TOKEN), failure.stderrTail());
        assertTrue(failure.stderrTail().contains(AppRunner.REDACTED_TOKEN), failure.stderrTail());
        assertFalse(failure.getMessage().contains(TOKEN), failure.getMessage());
    }

    @Test
    void startsTheDisplayOnTheFirstThreadOnMacOS() {
        Path java = workDir.resolve("bin").resolve("java");
        Path jar = workDir.resolve("nxmc-standalone.jar");

        List<String> onMac = new AppRunner(Map.of(), AppRunner.HEALTH_WINDOW, "Mac OS X").command(java, jar, "srv",
                4701, TOKEN);
        assertEquals(AppRunner.FIRST_THREAD_OPTION, onMac.get(1));
        assertEquals("-jar", onMac.get(2));

        List<String> onLinux = new AppRunner(Map.of(), AppRunner.HEALTH_WINDOW, "Linux").command(java, jar, "srv",
                4701, TOKEN);
        assertFalse(onLinux.contains(AppRunner.FIRST_THREAD_OPTION), onLinux.toString());
        assertEquals(List.of(java.toString(), "-jar", jar.toAbsolutePath().toString(), "-server=srv",
                "-token=" + TOKEN, "-auto"), onLinux);

        List<String> onWindows = windowsRunner(Map.of()).command(java, jar, "srv", 4701, TOKEN);
        assertEquals(List.of(java.toString(), "-jar", jar.toAbsolutePath().toString(), "-server=srv",
                "-token=" + TOKEN, "-auto"), onWindows);
    }

    @Test
    void handsAnIpv6AddressOverAsNxmcCanParseIt() {
        assertEquals("-server=[::1]", serverArgument("::1", 4701));
        assertEquals("-server=[::1]:1234", serverArgument("::1", 1234));
        assertEquals("-server=[fe80::1]", serverArgument("fe80::1", ServerEntry.DEFAULT_PORT));
        assertEquals("-server=[2001:db8::]", serverArgument("2001:db8::", ServerEntry.DEFAULT_PORT),
                "String.split " + "drops trailing empty tokens, so bare 2001:db8:: would read as host 2001");
    }

    @Test
    void keepsANonDefaultPortForAHostName() {
        assertEquals("-server=srv:1234", serverArgument("srv", 1234));
    }

    private String serverArgument(String host, int port) {
        Path java = workDir.resolve("bin").resolve("java");
        Path jar = workDir.resolve("nxmc-standalone.jar");

        return new AppRunner(Map.of(), AppRunner.HEALTH_WINDOW, "Linux").command(java, jar, host, port, TOKEN).stream().filter(argument -> argument.startsWith("-server=")).findFirst().orElseThrow();
    }

    @Test
    void stderrTailKeepsOnlyTheLastCharacters() {
        AppRunner.StderrTail tail = new AppRunner.StderrTail(10, null);

        tail.append("abc");
        assertEquals("abc", tail.text());

        tail.append("defghij");
        assertEquals("abcdefghij", tail.text());

        tail.append("KLM");
        assertEquals("defghijKLM", tail.text());

        tail.append("0123456789012");
        assertEquals("3456789012", tail.text());
    }

    @Test
    void stderrTailDrainsAStream() {
        AppRunner.StderrTail tail = new AppRunner.StderrTail(AppRunner.STDERR_TAIL_LIMIT, null);
        tail.drain(new java.io.ByteArrayInputStream("boom\nsecond line\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("boom\nsecond line\n", tail.text());
    }

    @Test
    void stderrTailRedactsATokenTheTrimWouldHaveCutInHalf() {
        AppRunner.StderrTail tail = new AppRunner.StderrTail(20, TOKEN);
        tail.append("unknown option -token=" + TOKEN);
        tail.append(", aborting\n");

        assertFalse(tail.text().contains(TOKEN), tail.text());
        for (int i = 4; i < TOKEN.length(); i++)
            assertFalse(tail.text().contains(TOKEN.substring(i)), tail.text());
        assertTrue(tail.text().endsWith(", aborting\n"), tail.text());
    }

    @Test
    void stderrTailRedactsATokenSplitAcrossTwoReads() {
        AppRunner.StderrTail tail = new AppRunner.StderrTail(AppRunner.STDERR_TAIL_LIMIT, TOKEN);
        tail.append("-token=" + TOKEN.substring(0, 5));
        tail.append(TOKEN.substring(5) + " rejected\n");

        assertEquals("-token=" + AppRunner.REDACTED_TOKEN + " rejected\n", tail.text());
    }

    private List<String> waitForArgs(Path argsFile) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (Files.isRegularFile(argsFile)) {
                List<String> lines = Files.readAllLines(argsFile);
                if (lines.size() == 3) {
                    return lines;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("fake nxmc never recorded its arguments in " + argsFile);
    }

    private Path fakeNxmc(Path argsFile, String stderr, int exitCode, long sleepMillis) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "JDK compiler required to build the fake nxmc jar");

        String template;
        try (InputStream in = AppRunnerTest.class.getResourceAsStream("/fake-nxmc/FakeNxmc.java.txt")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean echoArguments = ECHO_ARGUMENTS.equals(stderr);
        String source = template.replace("__ARGS_FILE__", JavaSource.pathLiteral(argsFile)).replace("__STDERR__",
                echoArguments ? "" : stderr).replace("__ECHO_ARGS__", Boolean.toString(echoArguments)).replace(
                        "__EXIT_CODE__", Integer.toString(exitCode)).replace("__SLEEP_MS__",
                Long.toString(sleepMillis));

        Path buildDir = Files.createDirectories(workDir.resolve("fake-nxmc-" + exitCode));
        Path sourceFile = buildDir.resolve("FakeNxmc.java");
        Files.writeString(sourceFile, source);
        assertEquals(0, compiler.run(null, null, null, "-d", buildDir.toString(), sourceFile.toString()), "fake nxmc "
                + "must compile");

        Path classFile = buildDir.resolve("FakeNxmc.class");
        assertTrue(Files.isRegularFile(classFile));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeNxmc");

        Path jar = buildDir.resolve("nxmc-standalone.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out,
                manifest)) {
            jarOut.putNextEntry(new JarEntry("FakeNxmc.class"));
            jarOut.write(Files.readAllBytes(classFile));
            jarOut.closeEntry();
        }

        assertNotEquals(0, Files.size(jar));
        return jar;
    }
}
