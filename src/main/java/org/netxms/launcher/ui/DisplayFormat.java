package org.netxms.launcher.ui;

import org.eclipse.swt.graphics.RGB;
import org.netxms.launcher.*;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class DisplayFormat {
    public static final String UNKNOWN = "unknown";

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final String NO_ADDRESS = "Enter the server address.";
    private static final String BAD_IPV6 = "Enter a valid IPv6 address, in brackets when a port follows: [::1] or [::1]:4701.";
    private static final String BAD_PORT = "Enter a port number between 1 and 65535.";
    private static final String SCHEME = "Enter the server address as host or host:port, without http:// or any other scheme.";

    private DisplayFormat() {
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return UNKNOWN;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes / 1024.0;
        int unit = 0;
        while ((round(value) >= 1024.0) && (unit < UNITS.length - 1)) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    public static String host(String url) {
        if ((url == null) || url.isBlank()) {
            return UNKNOWN;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return (host != null) ? host : url.trim();
        } catch (IllegalArgumentException e) {
            return url.trim();
        }
    }

    public static String version(ServerVersion version) {
        return (version != null) ? version.full() : UNKNOWN;
    }

    public static String lastLogin(String login) {
        return trimmed(login);
    }

    public static boolean canPrefillLogin(String current, String autoFilled) {
        return (current == null) || current.isEmpty() || current.equals(autoFilled);
    }

    public static FocusField fieldToFocus(String server, String login) {
        if (blank(server)) {
            return FocusField.SERVER;
        }
        return blank(login) ? FocusField.LOGIN : FocusField.PASSWORD;
    }

    private static boolean blank(String value) {
        return (value == null) || value.trim().isEmpty();
    }

    public static AddressParse parseAddress(String text) {
        String value = (text != null) ? text.trim() : "";
        if (value.isEmpty()) {
            return rejected(NO_ADDRESS);
        }

        if (value.contains("://")) {
            return rejected(SCHEME);
        }

        if (value.startsWith("[")) {
            return bracketed(value);
        }

        int colon = value.indexOf(':');
        if ((colon >= 0) && (value.indexOf(':', colon + 1) >= 0)) {
            return validIpv6(value) ? parsed(value, ServerEntry.DEFAULT_PORT) : rejected(BAD_IPV6);
        }

        String host = ((colon >= 0) ? value.substring(0, colon) : value).trim();
        if (host.isEmpty()) {
            return rejected(NO_ADDRESS);
        }
        if (colon < 0) {
            return parsed(host, ServerEntry.DEFAULT_PORT);
        }
        return withPort(host, value.substring(colon + 1));
    }

    private static AddressParse bracketed(String value) {
        int end = value.indexOf(']');
        if (end < 0) {
            return rejected(BAD_IPV6);
        }

        String literal = value.substring(1, end);
        if (!validIpv6(literal)) {
            return rejected(BAD_IPV6);
        }

        String rest = value.substring(end + 1);
        if (rest.isEmpty()) {
            return parsed(literal, ServerEntry.DEFAULT_PORT);
        }
        if (!rest.startsWith(":")) {
            return rejected(BAD_IPV6);
        }
        return withPort(literal, rest.substring(1));
    }

    private static boolean validIpv6(String literal) {
        if (literal.indexOf('%') >= 0) {
            return false;
        }
        try {
            return ("[" + literal + "]").equals(URI.create("//[" + literal + "]").getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static AddressParse withPort(String host, String text) {
        int port;
        try {
            port = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if ((port < 1) || (port > 65535)) {
            return rejected(BAD_PORT);
        }
        return parsed(host, port);
    }

    private static AddressParse parsed(String host, int port) {
        return new AddressParse(Optional.of(new ServerAddress(host, port)), Optional.empty());
    }

    private static AddressParse rejected(String message) {
        return new AddressParse(Optional.empty(), Optional.of(message));
    }

    public static String address(String host, int port) {
        return ServerEntry.compactAuthority(host, port);
    }

    public static String address(ServerEntry entry) {
        return address(entry.address(), entry.port());
    }

    public static Comparator<ServerEntry> byLastUsed() {
        return Comparator.comparing((ServerEntry e) -> lastUsedInstant(e).orElse(Instant.MIN), Comparator.reverseOrder()).thenComparing(ServerEntry::displayAddress, String.CASE_INSENSITIVE_ORDER);
    }

    private static Optional<Instant> lastUsedInstant(ServerEntry entry) {
        String value = entry.lastUsed();
        if ((value == null) || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String trimmed(String value) {
        return ((value != null) && !value.isBlank()) ? value.trim() : "";
    }

    public static String downloadStatus(String version, long downloaded, long total, Optional<String> eta) {
        long done = Math.max(downloaded, 0);
        String build = trimmed(version);
        String prefix = "Downloading nxmc" + (build.isEmpty() ? "" : (" " + build)) + " — ";
        if (total <= 0) {
            return prefix + size(done);
        }
        return prefix + percent(done, total) + "%" + eta.map(t -> ", " + t).orElse("");
    }

    static int percent(long downloaded, long total) {
        if (total <= 0) {
            return 0;
        }
        long done = Math.min(Math.max(downloaded, 0), total);
        return (int) ((100 * done) / total);
    }

    public static String downloadPrompt(String branch, ServerVersion version, String manifestHost) {
        String source = trimmed(manifestHost);
        return "No nxmc build for server branch " + branch + " is cached.\n" + "The server reports version " + version(version) + ".\n\n" + "Download the matching build" + (source.isEmpty() ? "" : (" from " + source)) + "?";
    }

    public static String updateCheckQuestion(String manifestHost) {
        return "Check for newer nxmc builds automatically?\n\n" + "The launcher will retrieve the list of available builds from " + trimmed(manifestHost) + ".\n" + "No information about you, your servers, or this computer is included in the request.\n" + "The hosting server may record your IP address, as with any web request.\n\n" + "Required builds are downloaded only after you confirm the download.\n" + "You can change this setting later in Settings.";
    }

    public static String updatePrompt(String branch, ServerVersion cached, ReleaseManifest.Release release) {
        return "A newer nxmc build is published for server branch " + branch + ".\n\n" + "Installed: " + version(cached) + "\n" + "Available: " + release.version() + " (" + size(release.size()) + " from " + host(release.url()) + ")\n\n" + "Download the newer build? The installed one is used if you decline.";
    }

    public static UpdateCheckResult updateCheckResult(PackageManager.CachedPackage cached, ReleaseManifest manifest, Optional<ReleaseManifest.Release> newer) {
        if (newer.isPresent()) {
            return new UpdateCheckResult(updateFound(cached, newer.get()) + " It will be offered next time you connect to a " + cached.branch() + " server.", false, UpdateCheckStamp.ARMED);
        }

        if (!manifest.branches().contains(cached.branch())) {
            return new UpdateCheckResult("The release manifest at " + manifest.source() + " publishes no nxmc builds for branch " + cached.branch() + ", so there is nothing to compare the cached build against.", false, UpdateCheckStamp.CHECKED);
        }

        return new UpdateCheckResult("Branch " + cached.branch() + " is up to date.", false, UpdateCheckStamp.CHECKED);
    }

    private static String updateFound(PackageManager.CachedPackage cached, ReleaseManifest.Release newer) {
        return "Branch " + cached.branch() + ": " + newer.version() + " is available (cached " + version(cached.version()) + ").";
    }

    public static UpdateCheckResult updateNotArmed(PackageManager.CachedPackage cached, ReleaseManifest.Release newer, String reason) {
        return new UpdateCheckResult(updateFound(cached, newer) + " The launcher could not record the request, so this check has" + " not arranged the offer; a later connect may still make it on its own: " + reason + ". Check again to record it.", true, UpdateCheckStamp.NONE);
    }

    public static UpdateCheckResult updateBranchGone(PackageManager.CachedPackage cached, ReleaseManifest.Release newer) {
        return new UpdateCheckResult(updateFound(cached, newer) + " The cached build it was compared against is no longer in the" + " cache, so there is nothing left to record the request on: " + startsOver(cached), true, UpdateCheckStamp.NONE);
    }

    public static UpdateCheckResult updateBranchGone(PackageManager.CachedPackage cached) {
        return new UpdateCheckResult("The cached " + cached.branch() + " build this check was made about is no longer in the cache," + " so there was nothing to compare the release manifest against: " + startsOver(cached), true, UpdateCheckStamp.NONE);
    }

    private static String startsOver(PackageManager.CachedPackage cached) {
        return "connecting to a " + cached.branch() + " server starts that branch again from an empty cache.";
    }

    public static UpdateCheckResult updateCheckResult(ManifestException failure) {
        String message = (failure.kind() == ManifestException.Kind.UNTRUSTED_URL) ? ("The release manifest points at a download the launcher does not trust.\n\n" + failure.getMessage()) : failure.getMessage();
        return new UpdateCheckResult(message, true, UpdateCheckStamp.NONE);
    }

    public static String lastUsed(Instant instant) {
        return lastUsed(instant, ZoneId.systemDefault());
    }

    public static String lastUsed(Instant instant, ZoneId zone) {
        return (instant != null) ? TIMESTAMP.withZone(zone).format(instant) : UNKNOWN;
    }

    public static String serverLastUsed(ServerEntry entry) {
        return serverLastUsed(entry, ZoneId.systemDefault());
    }

    public static String serverLastUsed(ServerEntry entry, ZoneId zone) {
        return lastUsed(lastUsedInstant(entry).orElse(null), zone);
    }

    public static String deleteBranchPrompt(PackageManager.CachedPackage cached) {
        return "Delete the cached nxmc build for branch " + cached.branch() + "?\n\n" + "Version: " + version(cached.version()) + "\n" + "Size: " + size(cached.size()) + "\n\n" + "It has to be installed again before a server on that branch can be started.";
    }

    public static String importReplacePrompt(PackageManager.CachedPackage cached, PackageManager.IdentifiedBuild identified) {
        ServerVersion offered = identified.version();
        ServerVersion held = cached.version();
        String change;
        if (offered.full().equals(held.full())) {
            change = "That is the version already cached, from the file you picked.";
        } else if (offered.isNewerPatchThan(held)) {
            change = "The file is a newer build than the cached one.";
        } else if (held.isNewerPatchThan(offered)) {
            change = "The file is an older build than the cached one.";
        } else {
            change = "The cached build is replaced by the one in the file.";
        }

        return "Branch " + identified.branch() + " already has a cached nxmc build.\n\n" + "Cached: " + version(cached.version()) + "\n" + "File: " + offered.full() + "\n\n" + change + "\n\nReplace it?";
    }

    public static String clearCachePrompt(int builds, int entries) {
        if (entries <= 0) {
            return "There is nothing in the cache.";
        }

        int unusable = Math.max(0, entries - builds);
        if (builds <= 0) {
            return "Delete " + unusable + " cache " + ((unusable == 1) ? "entry" : "entries") + " that cannot be read as an nxmc build?\n\nThe build for those branches has to be installed again before a server on" + " them can be started, and an entry that is not a directory this launcher created refuses that install until it is gone.";
        }

        String extra = (unusable > 0) ? ("\n\n" + unusable + " cache " + ((unusable == 1) ? "entry that cannot be read as a build is" : "entries that cannot be read as builds are") + " removed as well.") : "";
        return "Delete all " + builds + " cached nxmc " + ((builds == 1) ? "build" : "builds") + "?" + extra + "\n\nThey have to be installed again before a server on those branches can be started.";
    }

    public static String cacheSummary(int builds, long total, int entries) {
        int unusable = Math.max(0, entries - builds);
        if (builds <= 0) {
            return (unusable == 0) ? "Cache is empty." : (unusable + " unusable " + ((unusable == 1) ? "entry" : "entries") + ", no cached builds");
        }
        return builds + " cached, " + size(total) + " total" + ((unusable > 0) ? (", " + unusable + " unusable " + ((unusable == 1) ? "entry" : "entries")) : "");
    }

    public static String passwordExpiredMessage(int graceLogins, String rejection) {
        String reason = ((rejection != null) && !rejection.isBlank()) ? ("The server did not accept that password: " + rejection.trim() + "\n\n") : "";
        String remaining = (graceLogins > 0) ? (" You have " + graceLogins + " grace " + ((graceLogins == 1) ? "login" : "logins") + " left.") : "";
        return reason + "Your password has expired." + remaining + "\n\nEnter a new one, or cancel to start nxmc without changing it.";
    }

    public static String redownloadPrompt(LaunchFailure failure) {
        String exit = (failure.exitCode() != LaunchFailure.NO_EXIT_CODE) ? (" (exit code " + failure.exitCode() + ")") : "";
        String tail = failure.stderrTail();
        return "nxmc stopped right after it was started" + exit + ".\n\n" + (tail.isBlank() ? "" : (tail + "\n\n")) + "Delete the cached build and download it again?";
    }

    public static boolean isDarkColor(RGB rgb) {
        double y = (0.2126 * linear(rgb.red)) + (0.7152 * linear(rgb.green)) + (0.0722 * linear(rgb.blue));
        // upstream's linear branch engages below L* = 8, which is dark whichever branch answers; kept for exactness
        double f = (y > 0.008856) ? Math.pow(y, 1 / 3.0) : ((7.787 * y) + (16 / 116.0));
        return ((116.0 * f) - 16.0) < 50.0;
    }

    /**
     * One sRGB channel, normalized to 0..1 and linearized by the sRGB transfer function.
     */
    private static double linear(int channel) {
        double c = channel / 255.0;
        return (c > 0.04045) ? Math.pow((c + 0.055) / 1.055, 2.4) : (c / 12.92);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public enum FocusField {
        SERVER, LOGIN, PASSWORD
    }

    public enum UpdateCheckStamp {
        /**
         * A newer build is published: arm the branch so the next connect offers it, switch or no switch.
         */
        ARMED,
        /**
         * Nothing newer: the branch is current as of now, and the next check is a day away.
         */
        CHECKED,
        /**
         * The manifest did not answer, so the schedule is left exactly as it was.
         */
        NONE
    }

    public record ServerAddress(String host, int port) {
    }

    public record AddressParse(Optional<ServerAddress> address, Optional<String> error) {
        public AddressParse {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(error, "error");
            if (address.isPresent() == error.isPresent()) {
                throw new IllegalArgumentException("an address parse is either an address or a message");
            }
        }

        public boolean valid() {
            return address.isPresent();
        }
    }

    public record UpdateCheckResult(String message, boolean failure, UpdateCheckStamp stamp) {
        public UpdateCheckResult {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(stamp, "stamp");
        }
    }
}
