package org.netxms.launcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Pattern;


public final class ReleaseManifest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String HTTPS = "https";

    private final URI source;
    private final Map<String, Release> releases;

    private ReleaseManifest(URI source, Map<String, Release> releases) {
        this.source = source;
        this.releases = releases;
    }

    public static ReleaseManifest parse(URI source, String json) throws ManifestException {
        Document document;
        try {
            document = MAPPER.readValue(json, Document.class);
        } catch (JacksonException e) {
            throw new ManifestException(ManifestException.Kind.MALFORMED, "Release manifest at " + source + " is not "
                    + "valid JSON: " + e.getOriginalMessage(), e);
        }

        if ((document == null) || (document.releases() == null)) {
            throw new ManifestException(ManifestException.Kind.MALFORMED, "Release manifest at " + source + " has no "
                    + "\"releases\" section");
        }

        return new ReleaseManifest(source, new LinkedHashMap<>(document.releases()));
    }

    private static Release validate(URI source, String branch, Release release) throws ManifestException {
        if (release == null) {
            throw malformed(source, branch, "entry is null");
        }
        if (isBlank(release.version())) {
            throw malformed(source, branch, "\"version\" is missing");
        }
        Optional<ServerVersion> version = ServerVersion.parse(release.version());
        if (version.isEmpty()) {
            throw malformed(source, branch, "\"version\" is not a version number: " + release.version().trim());
        }
        if (!version.get().branchKey().equals(branch)) {
            throw malformed(source, branch,
                    "\"version\" " + release.version().trim() + " belongs to branch " + version.get().branchKey());
        }
        if (isBlank(release.url())) {
            throw malformed(source, branch, "\"url\" is missing");
        }
        if (release.size() <= 0) {
            throw malformed(source, branch, "\"size\" must be positive");
        }

        String sha256 = (release.sha256() != null) ? release.sha256().trim().toLowerCase(Locale.ROOT) : "";
        if (!SHA256.matcher(sha256).matches()) {
            throw malformed(source, branch, "\"sha256\" is not a 64 character hex digest");
        }

        return new Release(release.version().trim(), release.url().trim(), sha256, release.size());
    }

    private static ManifestException malformed(URI source, String branch, String problem) {
        return new ManifestException(ManifestException.Kind.MALFORMED, "Release manifest at " + source + " is " +
                "invalid" + " for branch " + branch + ": " + problem);
    }

    private static boolean isBlank(String s) {
        return (s == null) || s.trim().isEmpty();
    }

    public URI source() {
        return source;
    }

    public Set<String> branches() {
        return Collections.unmodifiableSet(releases.keySet());
    }

    public Optional<Release> release(String branch) throws ManifestException {
        if ((branch == null) || !releases.containsKey(branch)) {
            return Optional.empty();
        }

        Release release = validate(source, branch, releases.get(branch));
        checkUrlPolicy(release.url());
        return Optional.of(release);
    }

    void checkUrlPolicy(String url) throws ManifestException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ManifestException(ManifestException.Kind.UNTRUSTED_URL,
                    "Release URL is not a valid URI: " + url, e);
        }

        if (!HTTPS.equalsIgnoreCase(uri.getScheme())) {
            throw new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "Release URL is not HTTPS: " + url);
        }

        if ((uri.getHost() == null) || !uri.getHost().equalsIgnoreCase(source.getHost())) {
            throw new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "Release URL host does not match " +
                    "manifest host " + source.getHost() + ": " + url);
        }

        if (PackageManager.port(uri) != PackageManager.port(source)) {
            throw new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "Release URL port does not match " +
                    "manifest port " + PackageManager.port(source) + ": " + url);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(String version, String url, String sha256, long size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(Map<String, Release> releases) {
    }
}
