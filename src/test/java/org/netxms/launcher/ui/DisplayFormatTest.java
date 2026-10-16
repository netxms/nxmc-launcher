package org.netxms.launcher.ui;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.netxms.launcher.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DisplayFormatTest {
    private static final ReleaseManifest.Release RELEASE = new ReleaseManifest.Release("5.2.3", "https://netxms" +
            ".org/download/nxmc-5.2.3.jar", "a".repeat(64), 47_185_920L);
    private static final String BAD_IPV6 =
            "Enter a valid IPv6 address, in brackets when a port follows: [::1] or " + "[::1]:4701.";

    private static PackageManager.CachedPackage cached(String branch, String version) {
        return new PackageManager.CachedPackage(branch, Path.of("/cache/" + branch + "/nxmc-standalone.jar"),
                ServerVersion.parse(version).orElseThrow(), "a".repeat(64), Instant.parse("2026-07-23T10:00:00Z"),
                47_185_920L, CheckStamp.NEVER);
    }

    private static PackageManager.IdentifiedBuild identified(String version) {
        return new PackageManager.IdentifiedBuild(Path.of("/media/usb/nxmc-standalone.jar"),
                ServerVersion.parse(version).orElseThrow());
    }

    private static ReleaseManifest manifest(String branch, String version) throws ManifestException {
        return ReleaseManifest.parse(URI.create("https://netxms.org/nxmc-releases.json"),
                "{\"releases\":{\"" + branch + "\":{\"version\":\"" + version + "\",\"url\":\"https://netxms" + ".org"
                        + "/nxmc-" + version + ".jar\",\"sha256\":\"" + "c".repeat(64) + "\",\"size\":1024}}}");
    }

    private static RGB rgb(String hex) {
        int value = Integer.parseInt(hex, 16);
        return new RGB((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
    }

    private static String errorOf(String text) {
        DisplayFormat.AddressParse parsed = DisplayFormat.parseAddress(text);
        assertFalse(parsed.valid(), () -> "expected a rejection for [" + text + "]");
        return parsed.error().orElseThrow();
    }

    private static ServerEntry entry(String address, int port, String lastUsed) {
        return new ServerEntry(address, port, null, null, lastUsed);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0 B",
            "1, 1 B",
            "1023, 1023 B",
            "1024, 1.0 KB",
            "1536, 1.5 KB",
            "1048575, 1.0 MB",
            "1048576, 1.0 MB",
            "47185920, 45.0 MB",
            "1073741824, 1.0 GB",
            "1099511627776, 1.0 TB",
            "1125899906842624, 1024.0 TB"
    })
    void formatsSizes(long bytes, String expected) {
        assertEquals(expected, DisplayFormat.size(bytes));
    }

    @Test
    void reportsNegativeSizeAsUnknown() {
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.size(-1));
    }

    @ParameterizedTest
    @CsvSource({
            "https://netxms.org/download/nxmc.jar, netxms.org",
            "https://dev.example.com:8443/x.jar, dev.example.com",
            "http://netxms.org/x.jar, netxms.org"
    })
    void extractsHost(String url, String expected) {
        assertEquals(expected, DisplayFormat.host(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a url",
            "://broken",
            "/relative/path.jar"
    })
    void showsUnparseableUrlVerbatim(String url) {
        assertEquals(url, DisplayFormat.host(url));
    }

    @Test
    void reportsMissingUrlAsUnknown() {
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.host(null));
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.host("   "));
    }

    @Test
    void formatsVersion() {
        assertEquals("5.2.3", DisplayFormat.version(ServerVersion.parse("5.2.3").orElseThrow()));
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.version(null));
    }

    @Test
    void keepsUserColumnEmptyWhenNothingIsKnown() {
        assertEquals("", DisplayFormat.lastLogin(null));
        assertEquals("", DisplayFormat.lastLogin(""));
        assertEquals("admin", DisplayFormat.lastLogin(" admin "));
    }

    @Test
    void prefillsTheUserNameOnlyOverAnEmptyFieldOrAPreviousPrefill() {
        assertTrue(DisplayFormat.canPrefillLogin("", "admin"));
        assertTrue(DisplayFormat.canPrefillLogin(null, ""));
        assertTrue(DisplayFormat.canPrefillLogin("admin", "admin"), "a previous pre-fill may be replaced");
        assertFalse(DisplayFormat.canPrefillLogin("typed-by-hand", "admin"), "what the user typed must survive");
        assertFalse(DisplayFormat.canPrefillLogin("admin", ""), "nothing was pre-filled, so 'admin' was typed");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "nothing entered,'','',SERVER",
            "server only,srv,'',LOGIN",
            "server and login,srv,admin,PASSWORD",
            "login without a server,'',admin,SERVER",
            "blank server beside a login,'  ',admin,SERVER",
            "blank login,srv,' \t',LOGIN",
            "null server,NULL,admin,SERVER",
            "null login,srv,NULL,LOGIN"
    })
    void focusGoesToTheFirstFieldStillNeedingInputAndPasswordIsTheFallback(String state, String server, String login, DisplayFormat.FocusField expected) {
        assertEquals(expected, DisplayFormat.fieldToFocus(server, login), state);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 100, 0",
            "50, 100, 50",
            "100, 100, 100",
            "150, 100, 100",
            "-5, 100, 0",
            "10, 0, 0",
            "10, -1, 0",
            "999, 1000, 99",
            "9999, 10000, 99",
            "1, 1000, 0"
    })
    void computesPercentage(long downloaded, long total, int expected) {
        assertEquals(expected, DisplayFormat.percent(downloaded, total));
    }

    @Test
    void namesTheBuildAndPercentWhileDownloading() {
        assertEquals("Downloading nxmc 5.2.4 — 42%", DisplayFormat.downloadStatus("5.2.4", 42, 100, Optional.empty()));
    }

    @Test
    void appendsTheRemainingTimeWhenThereIsOne() {
        assertEquals("Downloading nxmc 5.2.4 — 42%, about 1:20 left", DisplayFormat.downloadStatus("5.2.4", 42, 100,
                Optional.of("about 1:20 left")));
        assertEquals("Downloading nxmc 5.2.4 — 97%, almost done", DisplayFormat.downloadStatus("5.2.4", 97, 100,
                Optional.of("almost done")));
    }

    @Test
    void reportsBytesWhenTheManifestPublishedNoSize() {
        assertEquals("Downloading nxmc 5.2.4 — 12.0 MB", DisplayFormat.downloadStatus("5.2.4", 12_582_912, 0,
                Optional.empty()));
        assertEquals("Downloading nxmc 5.2.4 — 12.0 MB", DisplayFormat.downloadStatus("5.2.4", 12_582_912, -1,
                Optional.of("about 1:20 left")), "without a total there is nothing to estimate against, so no ETA is "
                + "shown");
    }

    @Test
    void reportsTheBoundsOfADownload() {
        assertEquals("Downloading nxmc 5.2.4 — 0%", DisplayFormat.downloadStatus("5.2.4", 0, 100, Optional.empty()));
        assertEquals("Downloading nxmc 5.2.4 — 100%, almost done", DisplayFormat.downloadStatus("5.2.4", 100, 100,
                Optional.of("almost done")));
        assertEquals("Downloading nxmc 5.2.4 — 100%", DisplayFormat.downloadStatus("5.2.4", 150, 100,
                Optional.empty()), "more bytes than announced still reads as a finished download");
    }

    @Test
    void downloadStatusSurvivesAnUnnamedBuild() {
        assertEquals("Downloading nxmc — 42%", DisplayFormat.downloadStatus(null, 42, 100, Optional.empty()));
        assertEquals("Downloading nxmc — 42%", DisplayFormat.downloadStatus("  ", 42, 100, Optional.empty()));
    }

    @Test
    void downloadPromptNamesTheBranchTheServerVersionAndTheManifestHost() {
        String prompt = DisplayFormat.downloadPrompt("5.2", ServerVersion.parse("5.2.3").orElseThrow(), "netxms.org");
        assertTrue(prompt.contains("branch 5.2"), prompt);
        assertTrue(prompt.contains("5.2.3"), prompt);
        assertTrue(prompt.contains("from netxms.org?"), prompt);
    }

    @Test
    void downloadPromptClaimsNothingAboutABuildItHasNotLookedUpYet() {
        String prompt = DisplayFormat.downloadPrompt("5.2", ServerVersion.parse("5.2.3").orElseThrow(), "netxms.org");
        assertFalse(prompt.contains("Size"), prompt);
        assertTrue(prompt.contains("the matching build"), prompt);
    }

    @Test
    void downloadPromptDropsTheSourceItWasNotGiven() {
        assertTrue(DisplayFormat.downloadPrompt("5.2", ServerVersion.parse("5.2.3").orElseThrow(), "  ").endsWith(
                "Download the matching build?"), DisplayFormat.downloadPrompt("5.2",
                ServerVersion.parse("5.2.3").orElseThrow(), "  "));
    }

    @Test
    void updateCheckQuestionAsksAboutTheCheckAndSaysWhereItCanBeChanged() {
        String question = DisplayFormat.updateCheckQuestion("netxms.org");
        assertTrue(question.startsWith("Check for newer nxmc builds automatically?"), question);
        assertTrue(question.contains("Settings"), question);
    }

    @Test
    void updateCheckQuestionSaysTheDownloadQuestionIsAskedSeparately() {
        String question = DisplayFormat.updateCheckQuestion("netxms.org");
        assertTrue(question.contains("downloaded only after you confirm the download"), question);
    }

    @Test
    void updateCheckQuestionIsShortEnoughToBeRead() {
        String question = DisplayFormat.updateCheckQuestion("netxms.org");
        for (String line : question.split("\n"))
            assertTrue(line.length() <= 90, line);
    }

    @Test
    void updateCheckQuestionStatesWhatTheCheckTransmits() {
        String question = DisplayFormat.updateCheckQuestion("netxms.org");
        assertTrue(question.contains("No information about you, your servers, or this computer is included in the " + "request."), question);
        assertTrue(question.contains("may record your IP address, as with any web request"), question);
        assertTrue(question.contains("list of available builds from netxms.org"), question);
    }

    @Test
    void updateCheckQuestionNamesTheHostThatWouldActuallyBeReached() {
        String question = DisplayFormat.updateCheckQuestion("mirror.corp.internal");
        assertTrue(question.contains("list of available builds from mirror.corp.internal"), question);
        assertFalse(question.contains("netxms.org"), question);
    }

    @Test
    void updatePromptContrastsInstalledAndAvailable() {
        String prompt = DisplayFormat.updatePrompt("5.2", ServerVersion.parse("5.2.1").orElseThrow(), RELEASE);
        assertTrue(prompt.contains("5.2.1"), prompt);
        assertTrue(prompt.contains("5.2.3"), prompt);
        assertTrue(prompt.contains("netxms.org"), prompt);
    }

    @Test
    void aPublishedNewerBuildIsReportedAndArmsTheBranch() throws ManifestException {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateCheckResult(cached("5.2", "5.2.3"),
                manifest("5" + ".2", "5.2.4"), Optional.of(new ReleaseManifest.Release("5.2.4", "https://netxms" +
                        ".org/download/nxmc-5" + ".2.4.jar", "b".repeat(64), 100L)));
        assertFalse(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.ARMED, result.stamp());
        assertTrue(result.message().contains("Branch 5.2: 5.2.4 is available (cached 5.2.3)."), result.message());
        assertTrue(result.message().contains("It will be offered next time you connect to a 5.2 server."),
                result.message());
        assertFalse(result.message().contains("Download"), result.message());
    }

    @Test
    void anArmTheCacheRefusedTakesTheOfferBackInsteadOfPromisingIt() throws ManifestException {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateNotArmed(cached("5.2", "5.2.3"),
                new ReleaseManifest.Release("5.2.4", "https://netxms.org/download/nxmc-5.2.4.jar", "b".repeat(64),
                        100L),
                "Another launcher instance is downloading, starting or removing the build for branch " + "5" + ".2");
        assertTrue(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.NONE, result.stamp(), "there is nothing left to record");
        assertTrue(result.message().contains("Branch 5.2: 5.2.4 is available (cached 5.2.3)."), result.message());
        assertFalse(result.message().contains("It will be offered"), result.message());
        assertTrue(result.message().contains("Another launcher instance is downloading"), result.message());
        assertTrue(result.message().contains("this check has not arranged the offer"), result.message());
        assertTrue(result.message().contains("a later connect may still make it on its own"), result.message());
        assertFalse(result.message().contains("only a connect"), result.message());
    }

    @Test
    void anArmWithNoBranchLeftToCarryItSaysWhatTheNextConnectDoesInstead() throws ManifestException {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateBranchGone(cached("5.2", "5.2.3"),
                new ReleaseManifest.Release("5.2.4", "https://netxms.org/download/nxmc-5.2.4.jar", "b".repeat(64),
                        100L));
        assertTrue(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.NONE, result.stamp(), "there is nothing left to record it on");
        assertTrue(result.message().contains("Branch 5.2: 5.2.4 is available (cached 5.2.3)."), result.message());
        assertFalse(result.message().contains("It will be offered"), result.message());
        assertTrue(result.message().contains("starts that branch again from an empty cache"), result.message());
        assertFalse(result.message().contains("5.2.4 will"), result.message());
        assertFalse(result.message().contains("installs a build"), result.message());
    }

    @Test
    void anAnswerWithNoCachedBuildLeftToCompareIsNotReportedAsUpToDate() {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateBranchGone(cached("5.2", "5.2.3"));
        assertTrue(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.NONE, result.stamp(), "there is nothing left to record it on");
        assertFalse(result.message().contains("up to date"), result.message());
        assertTrue(result.message().contains("nothing to compare the release manifest against"), result.message());
        assertTrue(result.message().contains("starts that branch again from an empty cache"), result.message());
    }

    @Test
    void nothingNewerReportsTheBranchIsCurrentAndStampsTheCheck() throws ManifestException {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateCheckResult(cached("5.2", "5.2.3"),
                manifest("5" + ".2", "5.2.3"), Optional.empty());
        assertFalse(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.CHECKED, result.stamp());
        assertEquals("Branch 5.2 is up to date.", result.message());
    }

    @Test
    void aBranchTheManifestDoesNotPublishIsNotReportedAsUpToDate() throws ManifestException {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateCheckResult(cached("5.2", "5.2.3"),
                manifest("5" + ".1", "5.1.7"), Optional.empty());
        assertFalse(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.CHECKED, result.stamp());
        assertTrue(result.message().contains("publishes no nxmc builds for branch 5.2"), result.message());
        assertFalse(result.message().contains("up to date"), result.message());
    }

    @Test
    void anUntrustedReleaseUrlLeadsWithWhatItMeansAndRecordsNothing() {
        DisplayFormat.UpdateCheckResult result =
                DisplayFormat.updateCheckResult(new ManifestException(ManifestException.Kind.UNTRUSTED_URL, "Release "
                        + "5.2.4 points at http://evil.example.com/nxmc.jar"));
        assertTrue(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.NONE, result.stamp());
        assertTrue(result.message().startsWith("The release manifest points at a download the launcher does not " +
                "trust" + "."), result.message());
        assertTrue(result.message().contains("http://evil.example.com/nxmc.jar"), result.message());
    }

    @ParameterizedTest
    @EnumSource(value = ManifestException.Kind.class, names = {
            "FETCH_FAILED",
            "MALFORMED"
    })
    void aManifestThatDidNotAnswerIsReportedRatherThanSwallowed(ManifestException.Kind kind) {
        DisplayFormat.UpdateCheckResult result = DisplayFormat.updateCheckResult(new ManifestException(kind, "Cannot "
                + "download release manifest from https://netxms.org/x.json: timeout"));
        assertTrue(result.failure(), result.message());
        assertEquals(DisplayFormat.UpdateCheckStamp.NONE, result.stamp());
        assertEquals("Cannot download release manifest from https://netxms.org/x.json: timeout", result.message());
    }

    @Test
    void passwordExpiredMessageCountsGraceLoginsAndOffersToDecline() {
        String message = DisplayFormat.passwordExpiredMessage(3, null);
        assertTrue(message.contains("3 grace logins left"), message);
        assertTrue(message.contains("cancel"), message);
        assertTrue(DisplayFormat.passwordExpiredMessage(1, null).contains("1 grace login left"),
                DisplayFormat.passwordExpiredMessage(1, null));
    }

    @Test
    void passwordExpiredMessageOmitsAGraceLoginCountTheServerDidNotReport() {
        String message = DisplayFormat.passwordExpiredMessage(0, null);
        assertFalse(message.contains("grace"), message);
        assertTrue(message.contains("cancel"), message);
    }

    @Test
    void passwordExpiredMessageLeadsWithWhyTheLastPasswordWasTurnedDown() {
        String message = DisplayFormat.passwordExpiredMessage(2, "password is too weak");
        assertTrue(message.startsWith("The server did not accept that password: password is too weak"), message);
        assertTrue(message.contains("2 grace logins left"), message);

        assertTrue(DisplayFormat.passwordExpiredMessage(2, "   ").startsWith("Your password has expired."),
                DisplayFormat.passwordExpiredMessage(2, "   "));
    }

    @Test
    void redownloadPromptShowsExitCodeAndStderr() {
        LaunchFailure failure = new LaunchFailure(LaunchFailure.Kind.EARLY_EXIT, "nxmc exited", 1, "java.lang" +
                ".NoClassDefFoundError", null);
        String prompt = DisplayFormat.redownloadPrompt(failure);
        assertTrue(prompt.contains("exit code 1"), prompt);
        assertTrue(prompt.contains("NoClassDefFoundError"), prompt);
    }

    @Test
    void redownloadPromptOmitsUnknownExitCodeAndEmptyStderr() {
        LaunchFailure failure = new LaunchFailure(LaunchFailure.Kind.EARLY_EXIT, "nxmc exited");
        String prompt = DisplayFormat.redownloadPrompt(failure);
        assertFalse(prompt.contains("exit code"), prompt);
        assertTrue(prompt.contains("Delete the cached build"), prompt);
    }

    @Test
    void formatsLastUsedTimestampInTheGivenZone() {
        Instant instant = Instant.parse("2026-07-23T21:05:00Z");
        assertEquals("2026-07-23 21:05", DisplayFormat.lastUsed(instant, ZoneOffset.UTC));
        assertEquals("2026-07-24 06:05", DisplayFormat.lastUsed(instant, ZoneId.of("Asia/Tokyo")));
    }

    @Test
    void reportsMissingLastUsedAsUnknown() {
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.lastUsed(null, ZoneOffset.UTC));
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.lastUsed(null));
    }

    @Test
    void deleteBranchPromptNamesBranchVersionAndSize() {
        PackageManager.CachedPackage cached = new PackageManager.CachedPackage("5.2",
                Path.of("/cache/5" + ".2/nxmc" + "-standalone.jar"), ServerVersion.parse("5.2.3").orElseThrow(),
                "a".repeat(64), Instant.parse("2026" + "-07-23T10:00:00Z"), 47_185_920L, CheckStamp.NEVER);
        String prompt = DisplayFormat.deleteBranchPrompt(cached);
        assertTrue(prompt.contains("5.2"), prompt);
        assertTrue(prompt.contains("5.2.3"), prompt);
        assertTrue(prompt.contains("45.0 MB"), prompt);
    }

    @Test
    void deleteBranchPromptDoesNotPromiseADownload() {
        String prompt = DisplayFormat.deleteBranchPrompt(cached("5.2", "5.2.3"));
        assertTrue(prompt.contains("It has to be installed again before a server on that branch can be started."),
                prompt);
        assertFalse(prompt.contains("downloaded"), prompt);
    }

    @Test
    void importReplacePromptNamesBothVersionsAndTheBranch() {
        String prompt = DisplayFormat.importReplacePrompt(cached("5.2", "5.2.3"), identified("5.2.5"));
        assertTrue(prompt.contains("Branch 5.2 already has a cached nxmc build."), prompt);
        assertTrue(prompt.contains("Cached: 5.2.3"), prompt);
        assertTrue(prompt.contains("File: 5.2.5"), prompt);
        assertTrue(prompt.contains("Replace it?"), prompt);
    }

    @Test
    void importReplacePromptSaysWhichWayTheVersionMoves() {
        assertTrue(DisplayFormat.importReplacePrompt(cached("5.2", "5.2.3"), identified("5.2.5")).contains("The file "
                + "is a newer build than the cached one."));
        assertTrue(DisplayFormat.importReplacePrompt(cached("5.2", "5.2.5"), identified("5.2.3")).contains("The file "
                + "is an older build than the cached one."));
        assertTrue(DisplayFormat.importReplacePrompt(cached("5.2", "5.2.3"), identified("5.2.3")).contains("That is " + "the version already cached, from the file you picked."));
    }

    @Test
    void importReplacePromptFallsBackWhenNeitherVersionIsNewer() {
        String prompt = DisplayFormat.importReplacePrompt(cached("5.2", "5.2.3-SNAPSHOT"), identified("5.2.3"));
        assertTrue(prompt.contains("The cached build is replaced by the one in the file."), prompt);
        assertTrue(prompt.contains("Cached: 5.2.3-SNAPSHOT"), prompt);
    }

    @Test
    void clearCachePromptCountsBuilds() {
        assertTrue(DisplayFormat.clearCachePrompt(3, 3).contains("all 3 cached nxmc builds"),
                DisplayFormat.clearCachePrompt(3, 3));
        assertTrue(DisplayFormat.clearCachePrompt(1, 1).contains("all 1 cached nxmc build"),
                DisplayFormat.clearCachePrompt(1, 1));
        assertEquals("There is nothing in the cache.", DisplayFormat.clearCachePrompt(0, 0));
    }

    @Test
    void clearCachePromptNamesEntriesThatAreNotBuilds() {
        String mixed = DisplayFormat.clearCachePrompt(2, 3);
        assertTrue(mixed.contains("all 2 cached nxmc builds"), mixed);
        assertTrue(mixed.contains("1 cache entry that cannot be read as a build is removed as well"), mixed);

        String unusableOnly = DisplayFormat.clearCachePrompt(0, 2);
        assertTrue(unusableOnly.contains("2 cache entries that cannot be read as an nxmc build"), unusableOnly);
        assertTrue(unusableOnly.contains("has to be installed again"), unusableOnly);
        assertFalse(unusableOnly.contains("No build can be installed"), unusableOnly);
    }

    @Test
    void clearCachePromptDoesNotPromiseTheBuildsComeBack() {
        assertFalse(DisplayFormat.clearCachePrompt(3, 3).contains("downloaded"), DisplayFormat.clearCachePrompt(3, 3));
        assertTrue(DisplayFormat.clearCachePrompt(3, 3).contains("have to be installed again"),
                DisplayFormat.clearCachePrompt(3, 3));
        assertFalse(DisplayFormat.clearCachePrompt(0, 2).contains("downloaded"), DisplayFormat.clearCachePrompt(0, 2));
    }

    @Test
    void cacheSummaryCountsWhatTheListCannotShow() {
        assertEquals("Cache is empty.", DisplayFormat.cacheSummary(0, 0, 0));
        assertEquals("2 cached, 3.0 KB total", DisplayFormat.cacheSummary(2, 3072, 2));
        assertEquals("2 cached, 3.0 KB total, 1 unusable entry", DisplayFormat.cacheSummary(2, 3072, 3));
        assertEquals("2 unusable entries, no cached builds", DisplayFormat.cacheSummary(0, 0, 2));
    }

    @ParameterizedTest
    @CsvSource({
            "netxms.example.com, netxms.example.com, 4701",
            "'  netxms.example.com  ', netxms.example.com, 4701",
            "netxms.example.com:4701, netxms.example.com, 4701",
            "netxms.example.com:8443, netxms.example.com, 8443",
            "' netxms.example.com : 8443 ', netxms.example.com, 8443",
            "10.0.0.1:1, 10.0.0.1, 1",
            "10.0.0.1:65535, 10.0.0.1, 65535"
    })
    void parsesHostAndPort(String text, String host, int port) {
        DisplayFormat.AddressParse parsed = DisplayFormat.parseAddress(text);
        assertTrue(parsed.valid(), () -> parsed.error().orElse(""));
        assertEquals(new DisplayFormat.ServerAddress(host, port), parsed.address().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            ":4701",
            "  :  4701"
    })
    void rejectsAMissingAddress(String text) {
        assertEquals("Enter the server address.", errorOf(text));
    }

    @Test
    void rejectsNullComboText() {
        assertEquals("Enter the server address.", errorOf(null));
    }

    @ParameterizedTest
    @CsvSource({
            "::1, ::1, 4701",
            "[::1], ::1, 4701",
            "[::1]:4701, ::1, 4701",
            "[::1]:1234, ::1, 1234",
            "fe80::1, fe80::1, 4701",
            "2001:db8::1:4701, 2001:db8::1:4701, 4701",
            "::ffff:10.0.0.1, ::ffff:10.0.0.1, 4701",
            "::, ::, 4701",
            "1:2:3:4:5:6:7:8, 1:2:3:4:5:6:7:8, 4701",
            "2001:db8::, 2001:db8::, 4701",
            "[2001:db8::]:1234, 2001:db8::, 1234"
    })
    void parsesAnIPv6Address(String text, String host, int port) {
        DisplayFormat.AddressParse parsed = DisplayFormat.parseAddress(text);
        assertTrue(parsed.valid(), () -> parsed.error().orElse(""));
        assertEquals(new DisplayFormat.ServerAddress(host, port), parsed.address().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[nonsense]:1",
            "[::1",
            "[]",
            "[10.0.0.1]",
            "[:::1]",
            "::1:",
            "1:2:3"
    })
    void rejectsTextThatIsNotAnIPv6Address(String text) {
        assertEquals(BAD_IPV6, errorOf(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::1]?x",
            "::1]#f",
            "fe80::1]?evil.example.com",
            "2001:db8::1]?junk"
    })
    void rejectsALiteralThatClosesTheBracketItselfAndTrailsSomethingElse(String text) {
        assertEquals(BAD_IPV6, errorOf(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fe80::1%eth0",
            "[fe80::1%eth0]",
            "[fe80::1%eth0]:1234"
    })
    void rejectsAZoneIdentifier(String text) {
        assertEquals(BAD_IPV6, errorOf(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[::1]:0",
            "[::1]:70000",
            "[::1]:-1",
            "[::1]:junk",
            "[::1]:"
    })
    void reportsABadPortAfterAnIPv6LiteralAsABadPort(String text) {
        assertEquals("Enter a port number between 1 and 65535.", errorOf(text));
    }

    @Test
    void isLessLenientWithWhitespaceInsideBracketsThanOutsideThem() {
        assertEquals(BAD_IPV6, errorOf("[ ::1 ]"));
        assertEquals(BAD_IPV6, errorOf("[::1] : 1234"));
        assertEquals(BAD_IPV6, errorOf("[::1]x"));
    }

    @Test
    void storesAnIPv6AddressUnbracketedAndShowsItBackWithoutTheDefaultPort() {
        DisplayFormat.AddressParse parsed = DisplayFormat.parseAddress("[::1]:4701");
        assertEquals(new DisplayFormat.ServerAddress("::1", 4701), parsed.address().orElseThrow());
        assertEquals("[::1]", DisplayFormat.address("::1", 4701),
                "the combo renders back the shorter form the user " + "did not type");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://netxms.example.com:4701",
            "https://netxms.example.com",
            "nxmc://netxms.example.com",
            "http://::1",
            "http://[::1]:4701"
    })
    void rejectsAPastedUrlAsAUrlRatherThanAsAnIPv6Address(String text) {
        assertEquals("Enter the server address as host or host:port, without http:// or any other scheme.",
                errorOf(text));
    }

    @Test
    void anAddressParseIsEitherAnAddressOrAMessage() {
        DisplayFormat.ServerAddress address = new DisplayFormat.ServerAddress("netxms.example.com", 4701);
        assertThrows(IllegalArgumentException.class, () -> new DisplayFormat.AddressParse(Optional.of(address),
                Optional.of("both")));
        assertThrows(IllegalArgumentException.class, () -> new DisplayFormat.AddressParse(Optional.empty(),
                Optional.empty()));
        assertThrows(NullPointerException.class, () -> new DisplayFormat.AddressParse(null, Optional.of("no address")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "host:0",
            "host:65536",
            "host:-1",
            "host:junk",
            "host:",
            "host:4701x"
    })
    void rejectsAPortOutsideTheValidRange(String text) {
        assertEquals("Enter a port number between 1 and 65535.", errorOf(text));
    }

    @Test
    void hidesTheDefaultPortInTheComboText() {
        assertEquals("netxms.example.com", DisplayFormat.address("netxms.example.com", ServerEntry.DEFAULT_PORT));
        assertEquals("netxms.example.com:8443", DisplayFormat.address("netxms.example.com", 8443));
        assertEquals("netxms.example.com:8443", DisplayFormat.address(entry("netxms.example.com", 8443, null)));
        assertEquals("netxms.example.com", DisplayFormat.address(entry("netxms.example.com", ServerEntry.DEFAULT_PORT
                , null)));
    }

    @Test
    void bracketsAnIPv6AddressInTheComboAndShowsThePortOnlyWhenItIsNotTheDefault() {
        assertEquals("[::1]", DisplayFormat.address("::1", ServerEntry.DEFAULT_PORT));
        assertEquals("[::1]:1234", DisplayFormat.address("::1", 1234));
        assertEquals("[fe80::1]", DisplayFormat.address(entry("fe80::1", ServerEntry.DEFAULT_PORT, null)));
        assertEquals("[fe80::1]:1234", DisplayFormat.address(entry("fe80::1", 1234, null)));
    }

    @ParameterizedTest
    @CsvSource({
            "netxms.example.com, 4701",
            "netxms.example.com, 8443",
            "10.0.0.1, 1",
            "::1, 4701",
            "::1, 1234",
            "2001:db8::1:4701, 4701",
            "fe80::1, 8443",
            "::, 4701",
            "2001:db8::, 4701",
            "2001:db8::, 1234"
    })
    void displayedAddressParsesBackToItself(String host, int port) {
        DisplayFormat.AddressParse parsed = DisplayFormat.parseAddress(DisplayFormat.address(host, port));
        assertEquals(new DisplayFormat.ServerAddress(host, port), parsed.address().orElseThrow());
    }

    @Test
    void ordersHistoryByMostRecentUse() {
        ServerEntry oldest = entry("a.example.com", 4701, "2026-07-20T10:00:00Z");
        ServerEntry newest = entry("b.example.com", 4701, "2026-07-24T10:00:00Z");
        ServerEntry middle = entry("c.example.com", 4701, "2026-07-22T10:00:00Z");

        List<ServerEntry> sorted = new ArrayList<>(List.of(oldest, newest, middle));
        sorted.sort(DisplayFormat.byLastUsed());
        assertEquals(List.of(newest, middle, oldest), sorted);
    }

    @Test
    void sortsServersWithoutATimestampLastAndTiebreaksOnTheFullAddress() {
        ServerEntry used = entry("z.example.com", 4701, "2026-07-20T10:00:00Z");
        ServerEntry neverOnPort9 = entry("a.example.com", 9, null);
        ServerEntry neverOnDefault = entry("a.example.com", 4701, null);
        ServerEntry blank = entry("b.example.com", 4701, "  ");
        ServerEntry unparseable = entry("c.example.com", 4701, "yesterday");

        List<ServerEntry> sorted = new ArrayList<>(List.of(unparseable, neverOnDefault, blank, neverOnPort9, used));
        sorted.sort(DisplayFormat.byLastUsed());
        assertEquals(List.of(used, neverOnDefault, neverOnPort9, blank, unparseable), sorted);
    }

    @Test
    void formatsTheLastUseOfAServerInTheGivenZone() {
        assertEquals("2026-07-24 06:05", DisplayFormat.serverLastUsed(entry("a.example.com", 4701,
                "2026-07-23T21:05" + ":00Z"), ZoneId.of("Asia/Tokyo")));
    }

    @Test
    void reportsAServerNoProbeStampedAsUnknown() {
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.serverLastUsed(entry("a.example.com", 4701, null),
                ZoneOffset.UTC));
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.serverLastUsed(entry("a.example.com", 4701, "  "),
                ZoneOffset.UTC));
        assertEquals(DisplayFormat.UNKNOWN, DisplayFormat.serverLastUsed(entry("a.example.com", 4701, "yesterday"),
                ZoneOffset.UTC));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "win32 COLOR_BTNFACE,             F0F0F0, false",
            "GTK Adwaita,                     FAFAFA, false",
            "white,                           FFFFFF, false",
            "GTK Adwaita-dark,                353535, true",
            "macOS dark windowBackgroundColor, 282828, true",
            "inside the CIELAB linear branch, 121212, true",
            "black,                           000000, true"
    })
    void readsRealWidgetBackgroundsAsTheAppearanceTheyBelongTo(String surface, String hex, boolean dark) {
        assertEquals(dark, DisplayFormat.isDarkColor(rgb(hex)), surface + " #" + hex);
    }

    @Test
    void putsTheDarkThresholdBetweenGray119AndGray118() {
        assertFalse(DisplayFormat.isDarkColor(rgb("777777")));
        assertTrue(DisplayFormat.isDarkColor(rgb("767676")));
    }

    @Test
    void readsGray118AsDarkWhereARelativeLuminanceCutoffWouldNot() {
        assertTrue(DisplayFormat.isDarkColor(rgb("767676")),
                "Y = 0.1811 is above the 0.179 a luminance rule splits " + "on");
    }

    @Test
    void decidesOnLightnessRatherThanTheAverageOfTheChannels() {
        assertTrue(DisplayFormat.isDarkColor(rgb("0000FF")));
        assertFalse(DisplayFormat.isDarkColor(rgb("00FF00")));
    }
}
