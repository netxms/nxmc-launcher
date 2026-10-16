package org.netxms.launcher;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class AppDirs {
    private static final String APP_DIR = "nxmc-launcher";

    private final Path configHome;
    private final Path dataHome;

    private AppDirs(Path configHome, Path dataHome) {
        this.configHome = configHome;
        this.dataHome = dataHome;
    }

    public static AppDirs standard() {
        return of(System.getenv(), System.getProperty("os.name", ""));
    }

    static AppDirs of(Map<String, String> env, String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows") ? windows(env) : xdg(env);
    }

    private static AppDirs xdg(Map<String, String> env) {
        Path home = homeDir(env);
        return new AppDirs(base(env.get("XDG_CONFIG_HOME"), home.resolve(".config")), base(env.get("XDG_DATA_HOME"), home.resolve(".local").resolve("share")));
    }

    private static AppDirs windows(Map<String, String> env) {
        Path appData = Path.of(System.getProperty("user.home")).resolve("AppData");
        return new AppDirs(base(windowsValue(env, "APPDATA"), appData.resolve("Roaming")), base(windowsValue(env, "LOCALAPPDATA"), appData.resolve("Local")));
    }

    static String windowsValue(Map<String, String> env, String name) {
        String exact = env.get(name);
        if (exact != null) {
            return exact;
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Path homeDir(Map<String, String> env) {
        String home = env.get("HOME");
        if ((home == null) || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        return Path.of(home);
    }

    private static Path base(String value, Path fallback) {
        if ((value == null) || value.isBlank()) {
            return fallback;
        }

        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            return fallback;
        }
        return path.isAbsolute() ? path : fallback;
    }

    public Path configDir() {
        return configHome.resolve(APP_DIR);
    }

    public Path dataDir() {
        return dataHome.resolve(APP_DIR);
    }
}
