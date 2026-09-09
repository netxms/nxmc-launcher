package org.netxms.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AppRunner {
    static final Duration HEALTH_WINDOW = Duration.ofSeconds(3);
    static final int STDERR_TAIL_LIMIT = 8192;
    static final String POSIX_JAVA_EXECUTABLE = "java";
    static final String WINDOWS_JAVA_EXECUTABLE = "javaw.exe";
    static final String FIRST_THREAD_OPTION = "-XstartOnFirstThread";
    static final String REDACTED_TOKEN = "<token>";

    private static final Duration DRAIN_GRACE = Duration.ofMillis(500);

    private final Map<String, String> env;
    private final Duration healthWindow;
    private final String osName;

    public AppRunner() {
        this(System.getenv(), HEALTH_WINDOW);
    }

    AppRunner(Map<String, String> env, Duration healthWindow) {
        this(env, healthWindow, System.getProperty("os.name", ""));
    }

    AppRunner(Map<String, String> env, Duration healthWindow, String osName) {
        this.env = env;
        this.healthWindow = healthWindow;
        this.osName = (osName != null) ? osName : "";
    }

    static String redact(String text, String token) {
        return ((text == null) || (token == null) || token.isEmpty()) ? text : text.replace(token, REDACTED_TOKEN);
    }

    private static Path directory(String value) {
        if (value.isBlank()) {
            return null;
        }

        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean isExecutable(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }

    public Process run(Path jar, String host, int port, String token) throws LaunchFailure {
        Objects.requireNonNull(jar, "jar");
        if ((host == null) || host.isBlank()) {
            throw new IllegalArgumentException("Server address is required");
        }
        if ((token == null) || token.isBlank()) {
            throw new IllegalArgumentException("Authentication token is required");
        }

        if (!Files.isRegularFile(jar)) {
            throw new LaunchFailure(LaunchFailure.Kind.SPAWN_FAILED, "Cached build " + jar + " is missing");
        }

        Path java = javaBinary();
        List<String> command = command(java, jar, host, port, token);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new LaunchFailure(LaunchFailure.Kind.SPAWN_FAILED, "Cannot start " + java + ": " + e.getMessage(), e);
        }

        StderrTail tail = new StderrTail(STDERR_TAIL_LIMIT, token);
        Thread drain = new Thread(() -> tail.drain(process.getErrorStream()), "nxmc-stderr");
        drain.setDaemon(true);
        drain.start();

        try {
            if (process.waitFor(healthWindow.toMillis(), TimeUnit.MILLISECONDS)) {
                drain.join(DRAIN_GRACE.toMillis());
                int exitCode = process.exitValue();
                throw new LaunchFailure(LaunchFailure.Kind.EARLY_EXIT, "nxmc exited with code " + exitCode + " within " + healthWindow.toMillis() + " ms of starting", exitCode, tail.text(), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new LaunchFailure(LaunchFailure.Kind.SPAWN_FAILED, "Interrupted while waiting for nxmc to start", e);
        }

        return process;
    }

    List<String> command(Path java, Path jar, String host, int port, String token) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (isMacOS()) {
            command.add(FIRST_THREAD_OPTION);
        }
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.add("-server=" + ServerEntry.compactAuthority(host, port));
        command.add("-token=" + token);
        command.add("-auto");
        return command;
    }

    boolean isMacOS() {
        return osName.toLowerCase(Locale.ROOT).startsWith("mac");
    }

    boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    String javaExecutable() {
        return isWindows() ? WINDOWS_JAVA_EXECUTABLE : POSIX_JAVA_EXECUTABLE;
    }

    Path javaBinary() throws LaunchFailure {
        String executable = javaExecutable();

        String javaHome = envValue("JAVA_HOME");
        Path home = (javaHome != null) ? directory(javaHome.trim()) : null;
        if (home != null) {
            Path candidate = home.resolve("bin").resolve(executable);
            if (isExecutable(candidate)) {
                return candidate;
            }
        }

        for (Path dir : searchPath()) {
            Path candidate = dir.resolve(executable);
            if (isExecutable(candidate)) {
                return candidate;
            }
        }

        throw new LaunchFailure(LaunchFailure.Kind.JAVA_NOT_FOUND, "No Java runtime found: set JAVA_HOME or make '" + executable + "' available on PATH");
    }

    private String envValue(String name) {
        return isWindows() ? AppDirs.windowsValue(env, name) : env.get(name);
    }

    private List<Path> searchPath() {
        String path = envValue("PATH");
        if ((path == null) || path.isBlank()) {
            return List.of();
        }

        List<Path> dirs = new ArrayList<>();
        for (String entry : path.split(isWindows() ? ";" : ":")) {
            Path dir = directory(entry);
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    static final class StderrTail {
        private final int limit;
        private final String token;
        private final StringBuilder buffer = new StringBuilder();

        StderrTail(int limit, String token) {
            this.limit = limit;
            this.token = token;
        }

        synchronized void append(String text) {
            buffer.append(text);
            if ((token != null) && !token.isEmpty() && (buffer.indexOf(token) >= 0)) {
                String redacted = redact(buffer.toString(), token);
                buffer.setLength(0);
                buffer.append(redacted);
            }
            if (buffer.length() > limit) {
                buffer.delete(0, buffer.length() - limit);
            }
        }

        synchronized String text() {
            return buffer.toString();
        }

        void drain(InputStream in) {
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] chunk = new char[1024];
                int count;
                while ((count = reader.read(chunk)) > 0) {
                    append(new String(chunk, 0, count));
                }
            } catch (IOException ignored) {
            }
        }
    }
}
