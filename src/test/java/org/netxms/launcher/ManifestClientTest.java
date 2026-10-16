package org.netxms.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ManifestClientTest {
    private static final String VALID_MANIFEST = """
            {
              "releases": {
                "5.2": {
                  "version": "5.2.3",
                  "url": "https://%1$s/downloads/nxmc-5.2.3-standalone.jar",
                  "sha256": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "size": 41943040
                },
                "6.0": {
                  "version": "6.0.1",
                  "url": "https://%1$s/downloads/nxmc-6.0.1-standalone.jar",
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "size": 42991616
                }
              }
            }
            """;

    private static final URI MANIFEST_URL = URI.create("https://netxms.org/nxmc-releases.json");

    private HttpServer server;

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String manifestFor(URI source) {
        return VALID_MANIFEST.formatted(source.getAuthority());
    }

    private static ReleaseManifest.Release lookup(URI source, String releaseUrl) throws ManifestException {
        String json = """
                { "releases": { "5.2": { "version": "5.2.3", "url": "%s", "sha256": "%s", "size": 1024 } } }
                """.formatted(releaseUrl, "e".repeat(64));
        return ReleaseManifest.parse(source, json).release("5.2").orElseThrow();
    }

    private static ReleaseManifest.Release lookupIn(String json, String branch) throws ManifestException {
        return ReleaseManifest.parse(MANIFEST_URL, json).release(branch).orElseThrow();
    }

    private static ManifestException.Kind kindOf(ManifestCall call) {
        return assertThrows(ManifestException.class, call::run).kind();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        return URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort() + "/nxmc-releases.json");
    }

    private URI serve(HttpHandler handler) throws IOException {
        URI url = startServer();
        server.createContext("/nxmc-releases.json", handler);
        return url;
    }

    private URI serveBody(int status, String body) throws IOException {
        return serve(exchange -> respond(exchange, status, body));
    }

    @Test
    void fetchesAndParsesManifest() throws Exception {
        URI url = startServer();
        server.createContext("/nxmc-releases.json", exchange -> respond(exchange, 200, manifestFor(url)));

        ReleaseManifest manifest = new ManifestClient().fetch(url);
        assertEquals(url, manifest.source());
        assertEquals(Set.of("5.2", "6.0"), manifest.branches());

        ReleaseManifest.Release release = manifest.release("5.2").orElseThrow();
        assertEquals("5.2.3", release.version());
        assertEquals("https://" + url.getAuthority() + "/downloads/nxmc-5.2.3-standalone.jar", release.url());
        assertEquals(41943040L, release.size());
        assertEquals("a".repeat(64), release.sha256());
    }

    @Test
    void branchLookupIsEmptyForAbsentBranch() throws Exception {
        ReleaseManifest manifest = ReleaseManifest.parse(MANIFEST_URL, manifestFor(MANIFEST_URL));
        assertTrue(manifest.release("4.4").isEmpty());
        assertTrue(manifest.release(null).isEmpty());
        assertTrue(manifest.release("").isEmpty());
    }

    @Test
    void toleratesUnknownFields() throws Exception {
        URI url = serveBody(200, """
                {
                  "schemaVersion": 3,
                  "generated": "2026-07-23T10:00:00Z",
                  "releases": {
                    "5.2": {
                      "version": "5.2.3",
                      "url": "https://netxms.org/downloads/nxmc.jar",
                      "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                      "size": 1024,
                      "notes": "https://netxms.org/changelog",
                      "signature": null
                    }
                  }
                }
                """);

        ReleaseManifest manifest = new ManifestClient().fetch(url);
        assertEquals(Set.of("5.2"), manifest.branches());
    }

    @Test
    void emptyReleasesSectionIsValidButHasNoBranches() throws Exception {
        ReleaseManifest manifest = ReleaseManifest.parse(MANIFEST_URL, "{ \"releases\": {} }");
        assertTrue(manifest.branches().isEmpty());
        assertTrue(manifest.release("5.2").isEmpty());
    }

    @Test
    void rejectsMissingReleasesSection() {
        assertEquals(ManifestException.Kind.MALFORMED, kindOf(() -> ReleaseManifest.parse(MANIFEST_URL, "{}")));
        assertEquals(ManifestException.Kind.MALFORMED, kindOf(() -> ReleaseManifest.parse(MANIFEST_URL, "null")));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        URI url = serveBody(200, "{ \"releases\": ");
        ManifestException e = assertThrows(ManifestException.class, () -> new ManifestClient().fetch(url));
        assertEquals(ManifestException.Kind.MALFORMED, e.kind());
    }

    @Test
    void rejectsIncompleteReleaseEntries() {
        String template = """
                { "releases": { "5.2": { %s } } }
                """;
        String url = "\"url\": \"https://netxms.org/nxmc.jar\"";
        String version = "\"version\": \"5.2.3\"";
        String sha256 = "\"sha256\": \"" + "d".repeat(64) + "\"";
        String size = "\"size\": 1024";

        for (String entry : new String[]{
                url + "," + sha256 + "," + size,
                version + "," + sha256 + "," + size,
                version + "," + url + "," + size,
                version + "," + url + "," + sha256,
                version + "," + url + "," + sha256 + ", \"size\": 0",
                version + "," + url + ", \"sha256\": \"abc\", " + size,
                version + "," + url + ", \"sha256\": \"" + "z".repeat(64) + "\", " + size
        }) {
            String json = template.formatted(entry);
            ManifestException e = assertThrows(ManifestException.class, () -> lookupIn(json, "5.2"), json);
            assertEquals(ManifestException.Kind.MALFORMED, e.kind(), json);
        }
    }

    @Test
    void aBrokenEntryOnlyBreaksItsOwnBranch() {
        String json = """
                { "releases": {
                    "5.1": { "version": "nightly", "url": "https://netxms.org/nxmc.jar", "sha256": "%s", "size": 1024 },
                    "5.2": { "version": "5.2.3", "url": "https://netxms.org/nxmc.jar", "sha256": "%s", "size": 1024 },
                    "6.0": null } }
                """.formatted("d".repeat(64), "e".repeat(64));

        assertEquals("5.2.3", assertDoesNotThrow(() -> lookupIn(json, "5.2")).version());
        assertEquals(ManifestException.Kind.MALFORMED, kindOf(() -> lookupIn(json, "5.1")));
        assertEquals(ManifestException.Kind.MALFORMED, kindOf(() -> lookupIn(json, "6.0")));
    }

    @Test
    void rejectsVersionTheCacheCannotRead() {
        for (String version : new String[]{
                "nightly",
                "latest",
                "5",
                ""
        }) {
            String json = """
                    { "releases": { "5.2": { "version": "%s", "url": "https://netxms.org/nxmc.jar", "sha256": "%s", "size": 1024 } } }
                    """.formatted(version, "d".repeat(64));

            ManifestException e = assertThrows(ManifestException.class, () -> lookupIn(json, "5.2"), json);
            assertEquals(ManifestException.Kind.MALFORMED, e.kind(), json);
        }
    }

    @Test
    void rejectsVersionFromAnotherBranch() {
        for (String version : new String[]{
                "5.1.7",
                "6.0.1",
                "5.20.0"
        }) {
            String json = """
                    { "releases": { "5.2": { "version": "%s", "url": "https://netxms.org/nxmc.jar", "sha256": "%s", "size": 1024 } } }
                    """.formatted(version, "d".repeat(64));

            ManifestException e = assertThrows(ManifestException.class, () -> lookupIn(json, "5.2"), json);
            assertEquals(ManifestException.Kind.MALFORMED, e.kind(), json);
        }
    }

    @Test
    void acceptsVersionWithQualifierAndExtraComponentsOnItsOwnBranch() throws Exception {
        String json = """
                { "releases": { "5.2": { "version": "5.2.3.456-rc1", "url": "https://netxms.org/nxmc.jar", "sha256": "%s", "size": 1024 } } }
                """.formatted("d".repeat(64));

        ReleaseManifest manifest = ReleaseManifest.parse(MANIFEST_URL, json);
        assertEquals("5.2.3.456-rc1", manifest.release("5.2").orElseThrow().version());
    }

    @Test
    void rejectsNonHttpsReleaseUrl() {
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL,
                "http://netxms.org/nxmc" + ".jar")));
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL,
                "ftp://netxms.org/nxmc" + ".jar")));
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL, "/downloads/nxmc.jar")));
    }

    @Test
    void rejectsForeignReleaseHost() {
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL,
                "https://evil.example" + ".com/nxmc.jar")));
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL, "https://netxms.org" +
                ".evil" + ".example.com/nxmc.jar")));
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL, "https://netxms " +
                "org/nxmc" + ".jar")));
    }

    @Test
    void rejectsAnotherPortOnTheManifestHost() {
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(MANIFEST_URL, "https://netxms" + ".org" +
                ":8443/nxmc.jar")));
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(URI.create("https://netxms" + ".org" +
                ":8443/nxmc-releases.json"), "https://netxms.org/nxmc.jar")));
    }

    @Test
    void acceptsMatchingHostIgnoringCaseAndPolicyFollowsManifestOverride() throws Exception {
        assertEquals("https://NetXMS.ORG/nxmc.jar", lookup(MANIFEST_URL, "https://NetXMS.ORG/nxmc.jar").url());
        assertEquals("https://netxms.org:443/nxmc.jar", lookup(MANIFEST_URL, "https://netxms.org:443/nxmc.jar").url()
                , "the https default spelled out is the same origin, not another port");

        URI devManifest = URI.create("https://dev.example.com:8080/nxmc-releases.json");
        assertEquals("https://dev.example.com:8080/nxmc.jar", lookup(devManifest,
                "https://dev.example.com:8080/nxmc" + ".jar").url());
        assertEquals(ManifestException.Kind.UNTRUSTED_URL, kindOf(() -> lookup(devManifest,
                "https://netxms.org/nxmc" + ".jar")));
    }

    @Test
    void reportsHttpErrorStatus() throws Exception {
        URI url = serveBody(404, "not found");
        ManifestException e = assertThrows(ManifestException.class, () -> new ManifestClient().fetch(url));
        assertEquals(ManifestException.Kind.FETCH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void reportsTimeout() throws Exception {
        URI url = serve(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });

        ManifestClient client = new ManifestClient(Duration.ofMillis(200), Duration.ofMillis(200));
        ManifestException e = assertThrows(ManifestException.class, () -> client.fetch(url));
        assertEquals(ManifestException.Kind.FETCH_FAILED, e.kind());
    }

    @Test
    void reportsABodyThatNeverArrivesAfterTheHeaders() throws Exception {
        URI url = serve(exchange -> {
            exchange.sendResponseHeaders(200, 4096);
            exchange.getResponseBody().write("{ \"relea".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ManifestClient client = new ManifestClient(Duration.ofSeconds(10), Duration.ofMillis(300));
        ManifestException e = assertThrows(ManifestException.class, () -> client.fetch(url));
        assertEquals(ManifestException.Kind.FETCH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("no response body"), e.getMessage());
    }

    @Test
    void reportsUnreachableServer() throws Exception {
        URI url = serveBody(200, "{}");
        server.stop(0);
        server = null;

        ManifestException e = assertThrows(ManifestException.class, () -> new ManifestClient().fetch(url));
        assertEquals(ManifestException.Kind.FETCH_FAILED, e.kind());
    }

    @Test
    void refusesAnOversizedManifestBody() throws Exception {
        URI url = serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) ' ');
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++)
                    out.write(chunk);
            } catch (IOException e) {
                // expected: the client aborts the exchange once the cap is passed
            }
        });

        ManifestException e = assertThrows(ManifestException.class, () -> new ManifestClient().fetch(url));
        assertEquals(ManifestException.Kind.FETCH_FAILED, e.kind());
    }

    @Test
    void unusableManifestUrlIsReportedAsAFetchFailure() {
        assertEquals(ManifestException.Kind.FETCH_FAILED,
                kindOf(() -> new ManifestClient().fetch(URI.create("file" + ":/tmp/releases.json"))));
        assertEquals(ManifestException.Kind.FETCH_FAILED, kindOf(() -> new ManifestClient().fetch(URI.create(
                "releases.json"))));
    }

    @Test
    void configuredUrlDefaultsToNetxmsOrgAndHonoursOverride() throws Exception {
        String saved = System.getProperty(ManifestClient.MANIFEST_URL_PROPERTY);
        try {
            System.clearProperty(ManifestClient.MANIFEST_URL_PROPERTY);
            assertEquals(ManifestClient.DEFAULT_MANIFEST_URL, ManifestClient.configuredUrl());

            System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, "  ");
            assertEquals(ManifestClient.DEFAULT_MANIFEST_URL, ManifestClient.configuredUrl());

            System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, " http://dev.example.com/releases.json ");
            assertEquals(URI.create("http://dev.example.com/releases.json"), ManifestClient.configuredUrl());

            System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, "http://a b/x");
            assertEquals(ManifestException.Kind.FETCH_FAILED, kindOf(ManifestClient::configuredUrl));
        } finally {
            if (saved != null) {
                System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, saved);
            } else {
                System.clearProperty(ManifestClient.MANIFEST_URL_PROPERTY);
            }
        }
    }

    @Test
    void hostNamesTheManifestSourceAndNeverThrows() {
        String saved = System.getProperty(ManifestClient.MANIFEST_URL_PROPERTY);
        try {
            System.clearProperty(ManifestClient.MANIFEST_URL_PROPERTY);
            assertEquals("netxms.org", new ManifestClient().host());

            System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, "http://dev.example.com/releases.json");
            assertEquals("dev.example.com", new ManifestClient().host());

            System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, "http://a b/x");
            assertEquals("http://a b/x", new ManifestClient().host(), "an unusable value is still named, and " +
                    "reported" + " by the fetch");
            assertEquals(ManifestException.Kind.FETCH_FAILED, kindOf(() -> new ManifestClient().fetch()));

            System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, "file:/srv/nxmc-releases.json");
            assertEquals("file:/srv/nxmc-releases.json", new ManifestClient().host(),
                    "a URL with no host is still " + "named, never blank");
        } finally {
            if (saved != null) {
                System.setProperty(ManifestClient.MANIFEST_URL_PROPERTY, saved);
            } else {
                System.clearProperty(ManifestClient.MANIFEST_URL_PROPERTY);
            }
        }
    }

    @Test
    void theCheckSendsABareGetAndNothingAboutThisComputer() throws Exception {
        URI url = startServer();
        AtomicReference<URI> requested = new AtomicReference<>();
        Map<String, List<String>> sent = new HashMap<>();
        server.createContext("/nxmc-releases.json", exchange -> {
            requested.set(exchange.getRequestURI());
            sent.putAll(exchange.getRequestHeaders());
            respond(exchange, 200, manifestFor(url));
        });

        new ManifestClient().fetch(url);

        assertNull(requested.get().getQuery(), "updateCheckQuestion promises the check carries nothing about the user");
        assertEquals(List.of(ManifestClient.USER_AGENT), sent.get("User-agent"));
        String headers = sent.toString();
        assertFalse(headers.contains("Java-http-client"), headers);
        assertFalse(headers.contains(System.getProperty("java.version")), headers);
        assertFalse(headers.contains(System.getProperty("os.name")), headers);
        for (String identifying : new String[]{
                "Cookie",
                "Authorization",
                "Referer"
        })
            assertFalse(sent.containsKey(identifying), headers);
    }

    private interface ManifestCall {
        void run() throws ManifestException;
    }
}
