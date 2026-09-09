package org.netxms.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class Bootstrap {
    private static final String CORE_PREFIX = "BOOT-INF/core/";
    private static final String SWT_PREFIX = "BOOT-INF/swt/";
    private static final String SWT_ARTIFACT = "org.eclipse.swt.";
    private static final String LAUNCHER_CLASS = "org.netxms.launcher.Launcher";

    private Bootstrap() {
    }

    public static void main(String[] args) throws Exception {
        var osName = System.getProperty("os.name", "");
        var osArch = System.getProperty("os.arch", "");

        Optional<String> variant = swtVariant(osName, osArch);
        if (variant.isEmpty()) {
            System.err.println("nxmc launcher does not run on " + osName + " (" + osArch + "); supported platforms are Linux, macOS and Windows on x86_64 and aarch64");
            System.exit(1);
            return;
        }

        List<URL> classpath;
        try {
            classpath = extractClasspath(selfJar(), variant.get(), Paths.get(System.getProperty("java.io.tmpdir")));
        } catch (IOException | URISyntaxException e) {
            System.err.println("Cannot unpack the launcher jar: " + e.getMessage());
            System.exit(1);
            return;
        }

        launch(classpath, args);
    }

    static Optional<String> swtVariant(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);

        String platform;
        if (os.startsWith("linux")) {
            platform = "gtk.linux";
        } else if (os.startsWith("mac")) {
            platform = "cocoa.macosx";
        } else if (os.startsWith("windows")) {
            platform = "win32.win32";
        } else {
            return Optional.empty();
        }

        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return Optional.of(platform + ".x86_64");
        }
        if (arch.equals("aarch64")) {
            return Optional.of(platform + ".aarch64");
        }
        return Optional.empty();
    }

    static List<URL> extractClasspath(Path selfJar, String variant, Path tempDir) throws IOException {
        try (JarFile jar = new JarFile(selfJar.toFile())) {
            List<JarEntry> core = new ArrayList<>();
            List<JarEntry> fragments = new ArrayList<>();
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (nestedJar(entry, CORE_PREFIX)) {
                    core.add(entry);
                } else if (nestedJar(entry, SWT_PREFIX) && fileName(entry).startsWith(SWT_ARTIFACT + variant + "-")) {
                    fragments.add(entry);
                }
            }

            if (fragments.isEmpty()) {
                throw new IOException(selfJar + " carries no SWT fragment for " + variant + " (expected " + SWT_PREFIX + SWT_ARTIFACT + variant + "-<version>.jar)");
            }
            if (fragments.size() > 1) {
                throw new IOException(selfJar + " carries " + fragments.size() + " SWT fragments for " + variant + " (" + names(fragments) + "); rebuild with 'mvn clean package'");
            }

            Path scratch = scratchDir(tempDir);
            core.sort(Comparator.comparing(JarEntry::getName));
            List<URL> classpath = new ArrayList<>();
            for (JarEntry entry : core)
                classpath.add(extract(jar, entry, scratch));
            classpath.add(extract(jar, fragments.get(0), scratch));
            return classpath;
        }
    }

    private static Path scratchDir(Path tempDir) throws IOException {
        Path dir = Files.createTempDirectory(tempDir, "nxmc-launcher-");
        dir.toFile().deleteOnExit();
        return dir;
    }

    static void launch(List<URL> classpath, String[] args) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(classpath.toArray(new URL[0]), Bootstrap.class.getClassLoader())) {
            Method main = loader.loadClass(LAUNCHER_CLASS).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw (Error) cause;
            }
        }
    }

    private static Path selfJar() throws IOException, URISyntaxException {
        CodeSource source = Bootstrap.class.getProtectionDomain().getCodeSource();
        if ((source == null) || (source.getLocation() == null)) {
            throw new IOException("cannot locate the standalone jar this bootstrap was started from");
        }

        Path path = Paths.get(source.getLocation().toURI());
        if (!Files.isRegularFile(path)) {
            throw new IOException(path + " is not the standalone jar; start it with 'java -jar'");
        }
        return path;
    }

    private static URL extract(JarFile jar, JarEntry entry, Path tempDir) throws IOException {
        String name = fileName(entry);
        Path file = Files.createTempFile(tempDir, name.substring(0, name.length() - ".jar".length()) + "-", ".jar");
        file.toFile().deleteOnExit();
        try (InputStream in = jar.getInputStream(entry); OutputStream out = Files.newOutputStream(file)) {
            in.transferTo(out);
        }
        return file.toUri().toURL();
    }

    private static boolean nestedJar(JarEntry entry, String prefix) {
        String name = entry.getName();
        return name.startsWith(prefix) && name.endsWith(".jar");
    }

    private static String fileName(JarEntry entry) {
        String name = entry.getName();
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static String names(List<JarEntry> entries) {
        StringJoiner joiner = new StringJoiner(", ");
        for (JarEntry entry : entries)
            joiner.add(fileName(entry));
        return joiner.toString();
    }
}
