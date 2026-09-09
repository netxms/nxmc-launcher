package org.netxms.launcher;

import java.util.Objects;
import java.util.Optional;


public record ServerVersion(int major, int minor, int patch, int build, String full) {
    // CMD_REQUEST_AUTH_TOKEN and nxmc's -token= autologin both first shipped in NetXMS 5.0.0.
    public static final int MINIMUM_SUPPORTED_MAJOR = 5;

    private static final int MAX_COMPONENTS = 4;

    public static Optional<ServerVersion> parse(String version) {
        if (version == null) {
            return Optional.empty();
        }

        String full = version.trim();
        if (full.isEmpty()) {
            return Optional.empty();
        }

        int qualifier = indexOfQualifier(full);
        String numeric = (qualifier >= 0) ? full.substring(0, qualifier) : full;

        String[] parts = numeric.split("\\.", -1);
        if ((parts.length < 2) || (parts.length > MAX_COMPONENTS)) {
            return Optional.empty();
        }

        int[] components = new int[MAX_COMPONENTS];
        for (int i = 0; i < parts.length; i++) {
            int value = parseComponent(parts[i]);
            if (value < 0) {
                return Optional.empty();
            }
            components[i] = value;
        }

        return Optional.of(new ServerVersion(components[0], components[1], components[2], components[3], full));
    }

    private static int indexOfQualifier(String s) {
        int dash = s.indexOf('-');
        int plus = s.indexOf('+');
        if (dash < 0) {
            return plus;
        }
        if (plus < 0) {
            return dash;
        }
        return Math.min(dash, plus);
    }

    private static int parseComponent(String s) {
        if (s.isEmpty() || (s.length() > 9)) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if ((s.charAt(i) < '0') || (s.charAt(i) > '9')) {
                return -1;
            }
        }
        return Integer.parseInt(s);
    }

    public String branchKey() {
        return major + "." + minor;
    }

    public boolean isSupported() {
        return major >= MINIMUM_SUPPORTED_MAJOR;
    }

    public boolean isNewerPatchThan(ServerVersion other) {
        Objects.requireNonNull(other, "other");
        if ((major != other.major) || (minor != other.minor)) {
            return false;
        }
        return (patch != other.patch) ? (patch > other.patch) : (build > other.build);
    }
}
