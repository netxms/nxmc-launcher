package org.netxms.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class LauncherVersion {
    public static final String VERSION = load();

    private LauncherVersion() {
    }

    private static String load() {
        Properties properties = new Properties();
        try (InputStream in = LauncherVersion.class.getResourceAsStream("version.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }
}
