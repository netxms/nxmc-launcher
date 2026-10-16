package org.netxms.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppDirsTest {
    private static final String XDG_OS = "Linux";
    private static final String WINDOWS_OS = "Windows 11";

    @TempDir
    Path tmp;

    private static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                env.put(pairs[i], pairs[i + 1]);
            }
        }
        return env;
    }

    private static Path appData() {
        return Path.of(System.getProperty("user.home")).resolve("AppData");
    }

    private Path home() {
        return tmp.resolve("home");
    }

    @Test
    void usesExplicitXdgVariables() {
        Path cfg = tmp.resolve("cfg");
        Path data = tmp.resolve("data");
        AppDirs dirs = AppDirs.of(env("HOME", home().toString(), "XDG_CONFIG_HOME", cfg.toString(), "XDG_DATA_HOME",
                data.toString()), XDG_OS);
        assertEquals(cfg.resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(data.resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void fallsBackToHomeWhenVariablesUnset() {
        AppDirs dirs = AppDirs.of(env("HOME", home().toString()), XDG_OS);
        assertEquals(home().resolve(".config").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(home().resolve(".local").resolve("share").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "relative/path",
            ".config"
    })
    @NullSource
    void ignoresBlankAndRelativeValues(String value) {
        AppDirs dirs = AppDirs.of(env("HOME", home().toString(), "XDG_CONFIG_HOME", value, "XDG_DATA_HOME", value),
                XDG_OS);
        assertEquals(home().resolve(".config").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(home().resolve(".local").resolve("share").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void variablesAreResolvedIndependently() {
        Path data = tmp.resolve("var").resolve("data");
        AppDirs dirs = AppDirs.of(env("HOME", home().toString(), "XDG_DATA_HOME", data.toString()), XDG_OS);
        assertEquals(home().resolve(".config").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(data.resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void fallsBackToUserHomePropertyWhenHomeUnset() {
        Path expected = Path.of(System.getProperty("user.home"));
        AppDirs dirs = AppDirs.of(env(), XDG_OS);
        assertEquals(expected.resolve(".config").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(expected.resolve(".local").resolve("share").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void windowsVariablesAreIgnoredOnXdgPlatforms() {
        AppDirs dirs = AppDirs.of(env("HOME", home().toString(), "APPDATA", tmp.resolve("Roaming").toString(),
                "LOCALAPPDATA", tmp.resolve("Local").toString()), XDG_OS);
        assertEquals(home().resolve(".config").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(home().resolve(".local").resolve("share").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void usesExplicitWindowsVariables() {
        Path roaming = tmp.resolve("Roaming");
        Path local = tmp.resolve("Local");
        AppDirs dirs = AppDirs.of(env("APPDATA", roaming.toString(), "LOCALAPPDATA", local.toString()), WINDOWS_OS);
        assertEquals(roaming.resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(local.resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void fallsBackToUserProfileWhenWindowsVariablesUnset() {
        AppDirs dirs = AppDirs.of(env(), WINDOWS_OS);
        assertEquals(appData().resolve("Roaming").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(appData().resolve("Local").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "relative/path",
            "AppData"
    })
    @NullSource
    void ignoresBlankAndRelativeWindowsValues(String value) {
        AppDirs dirs = AppDirs.of(env("APPDATA", value, "LOCALAPPDATA", value), WINDOWS_OS);
        assertEquals(appData().resolve("Roaming").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(appData().resolve("Local").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void windowsVariablesAreResolvedIndependently() {
        Path local = tmp.resolve("Local");
        AppDirs dirs = AppDirs.of(env("LOCALAPPDATA", local.toString()), WINDOWS_OS);
        assertEquals(appData().resolve("Roaming").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(local.resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void xdgVariablesAreIgnoredOnWindows() {
        AppDirs dirs = AppDirs.of(env("HOME", home().toString(), "XDG_CONFIG_HOME", tmp.resolve("cfg").toString(),
                "XDG_DATA_HOME", tmp.resolve("data").toString()), WINDOWS_OS);
        assertEquals(appData().resolve("Roaming").resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(appData().resolve("Local").resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void windowsVariablesAreMatchedWithoutRegardToCase() {
        Path roaming = tmp.resolve("Roaming");
        Path local = tmp.resolve("Local");
        AppDirs dirs = AppDirs.of(env("AppData", roaming.toString(), "LocalAppData", local.toString()), WINDOWS_OS);
        assertEquals(roaming.resolve("nxmc-launcher"), dirs.configDir());
        assertEquals(local.resolve("nxmc-launcher"), dirs.dataDir());
    }

    @Test
    void xdgVariablesAreMatchedExactly() {
        AppDirs dirs = AppDirs.of(env("HOME", home().toString(), "xdg_config_home", tmp.resolve("cfg").toString()),
                XDG_OS);
        assertEquals(home().resolve(".config").resolve("nxmc-launcher"), dirs.configDir());
    }

    @Test
    void ignoresAValueThatIsNotAPathAtAll() {
        String unusable = "C:\\bad\u0000name";
        assertEquals(AppDirs.of(env(), WINDOWS_OS).configDir(),
                AppDirs.of(env("APPDATA", unusable), WINDOWS_OS).configDir());
        assertEquals(AppDirs.of(env("HOME", home().toString()), XDG_OS).dataDir(), AppDirs.of(env("HOME",
                home().toString(), "XDG_DATA_HOME", unusable), XDG_OS).dataDir());
    }

    @Test
    void standardResolvesAgainstProcessEnvironment() {
        AppDirs expected = AppDirs.of(System.getenv(), System.getProperty("os.name", ""));
        assertEquals(expected.configDir(), AppDirs.standard().configDir());
        assertEquals(expected.dataDir(), AppDirs.standard().dataDir());
    }
}
