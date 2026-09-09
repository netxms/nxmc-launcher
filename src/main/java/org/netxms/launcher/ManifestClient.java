package org.netxms.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ManifestClient implements ConnectFlow.ManifestSource {
    public static final String MANIFEST_URL_PROPERTY = "nxmc.launcher.manifest";
    public static final URI DEFAULT_MANIFEST_URL = URI.create("https://netxms.org/nxmc-releases.json");

    static final String USER_AGENT = "nxmc-launcher";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public ManifestClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    ManifestClient(Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static URI configuredUrl() throws ManifestException {
        String override = System.getProperty(MANIFEST_URL_PROPERTY);
        if ((override == null) || override.trim().isEmpty()) {
            return DEFAULT_MANIFEST_URL;
        }

        try {
            return new URI(override.trim());
        } catch (Exception e) {
            throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Invalid " + MANIFEST_URL_PROPERTY + " value: " + override, e);
        }
    }

    @Override
    public String host() {
        try {
            URI url = configuredUrl();
            String host = url.getHost();
            return (host != null) ? host : url.toString();
        } catch (ManifestException e) {
            return System.getProperty(MANIFEST_URL_PROPERTY, "").trim();
        }
    }

    @Override
    public ReleaseManifest fetch() throws ManifestException {
        return fetch(configuredUrl());
    }

    public ReleaseManifest fetch(URI url) throws ManifestException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(url).timeout(requestTimeout).header("Accept", "application/json").header("User-Agent", USER_AGENT).GET().build();
        } catch (IllegalArgumentException e) {
            throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Cannot download release manifest from " + url + ": " + e.getMessage(), e);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Cannot download release manifest from " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Interrupted while downloading release manifest from " + url, e);
        }

        String body;
        InputStream stream = response.body();
        ResponseDeadline deadline = new ResponseDeadline(stream, requestTimeout);
        try (InputStream in = stream) {
            if (response.statusCode() != 200) {
                throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Cannot download release manifest from " + url + ": server returned HTTP " + response.statusCode());
            }

            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (deadline.expired()) {
                throw new ManifestException(ManifestException.Kind.FETCH_FAILED, stalledMessage(url));
            }
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Release manifest at " + url + " is larger than " + MAX_BODY_BYTES + " bytes");
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (deadline.expired()) {
                throw new ManifestException(ManifestException.Kind.FETCH_FAILED, stalledMessage(url), e);
            }
            throw new ManifestException(ManifestException.Kind.FETCH_FAILED, "Cannot download release manifest from " + url + ": " + e.getMessage(), e);
        } finally {
            deadline.close();
        }

        return ReleaseManifest.parse(url, body);
    }

    private String stalledMessage(URI url) {
        return "Cannot download release manifest from " + url + ": no response body within " + requestTimeout.toMillis() + " ms";
    }
}
