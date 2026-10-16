package org.netxms.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ServerRegistryTest {
    @TempDir
    Path configDir;

    private Path file() {
        return configDir.resolve(ServerRegistry.FILE_NAME);
    }

    private String rawJson() throws IOException {
        return Files.readString(file(), StandardCharsets.UTF_8);
    }

    private void writeFile(String content) throws IOException {
        Files.writeString(file(), content, StandardCharsets.UTF_8);
    }

    @Test
    void firstRunYieldsEmptyRegistryWithoutCreatingFile() {
        ServerRegistry registry = ServerRegistry.load(configDir.resolve("missing"));
        assertTrue(registry.entries().isEmpty());
        assertTrue(registry.loadWarning().isEmpty());
        assertFalse(Files.exists(registry.file()));
    }

    @Test
    void roundTripsEntries() throws IOException {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(new ServerEntry("srv1.example.com", 4701, "admin", "5.2.3", null));
        registry.addOrUpdate(ServerEntry.of("srv2.example.com", 4702));
        registry.save();

        ServerRegistry reloaded = ServerRegistry.load(configDir);
        assertEquals(registry.entries(), reloaded.entries());
        assertTrue(reloaded.loadWarning().isEmpty());
        assertEquals("admin", reloaded.find("srv1.example.com", 4701).orElseThrow().lastLogin());
        assertEquals("5.2.3", reloaded.find("srv1.example.com", 4701).orElseThrow().lastSeenVersion());
        assertNull(reloaded.find("srv2.example.com", 4702).orElseThrow().lastLogin());
    }

    @Test
    void addOrUpdateReplacesMatchingEntryInPlace() throws IOException {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("a", 4701));
        registry.addOrUpdate(ServerEntry.of("b", 4701));
        registry.addOrUpdate(new ServerEntry("A", 4701, "admin", "5.2.3", null));

        List<ServerEntry> entries = registry.entries();
        assertEquals(2, entries.size());
        assertEquals("admin", entries.get(0).lastLogin());
        assertEquals("b", entries.get(1).address());
    }

    @Test
    void sameAddressOnDifferentPortIsSeparateEntry() {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("srv", 4701));
        registry.addOrUpdate(ServerEntry.of("srv", 4702));
        assertEquals(2, registry.entries().size());
    }

    @Test
    void removesEntry() throws IOException {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("srv", 4701));
        assertTrue(registry.remove("srv", 4701));
        assertFalse(registry.remove("srv", 4701));
        registry.save();

        assertTrue(ServerRegistry.load(configDir).entries().isEmpty());
    }

    @Test
    void entriesViewIsNotLiveAndNotModifiable() {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("srv", 4701));
        List<ServerEntry> snapshot = registry.entries();
        registry.addOrUpdate(ServerEntry.of("other", 4701));

        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(ServerEntry.of("x", 4701)));
    }

    @Test
    void saveCreatesMissingDirectoriesAndLeavesNoTempFiles() throws IOException {
        Path nested = configDir.resolve("deep/config");
        ServerRegistry registry = ServerRegistry.load(nested);
        registry.addOrUpdate(ServerEntry.of("srv", 4701));
        registry.save();

        try (var files = Files.list(nested)) {
            assertEquals(List.of(ServerRegistry.FILE_NAME, ServerRegistry.FILE_NAME + ServerRegistry.LOCK_SUFFIX),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void saveKeepsAnotherInstancesChangesToOtherServers() throws IOException {
        ServerRegistry first = ServerRegistry.load(configDir);
        first.addOrUpdate(ServerEntry.of("shared.example.com", 4701));
        first.save();

        ServerRegistry second = ServerRegistry.load(configDir);

        first.addOrUpdate(new ServerEntry("shared.example.com", 4701, "admin", "5.2.3", null));
        first.addOrUpdate(ServerEntry.of("only-in-first.example.com", 4701));
        first.save();

        second.addOrUpdate(ServerEntry.of("only-in-second.example.com", 4701));
        second.save();

        ServerRegistry reloaded = ServerRegistry.load(configDir);
        assertEquals(3, reloaded.entries().size(), reloaded.entries().toString());
        assertEquals("admin", reloaded.find("shared.example.com", 4701).orElseThrow().lastLogin());
        assertTrue(reloaded.find("only-in-first.example.com", 4701).isPresent());
        assertTrue(reloaded.find("only-in-second.example.com", 4701).isPresent());
        assertEquals(3, second.entries().size(), "the saving instance holds the merged result, not just what it knew");
    }

    @Test
    void saveReplaysARemovalOntoTheCurrentFile() throws IOException {
        ServerRegistry first = ServerRegistry.load(configDir);
        first.addOrUpdate(ServerEntry.of("doomed.example.com", 4701));
        first.save();

        ServerRegistry second = ServerRegistry.load(configDir);
        second.addOrUpdate(ServerEntry.of("added-later.example.com", 4701));
        second.save();

        first.remove("doomed.example.com", 4701);
        first.save();

        ServerRegistry reloaded = ServerRegistry.load(configDir);
        assertEquals(List.of(ServerEntry.of("added-later.example.com", 4701)), reloaded.entries());
    }

    @Test
    void saveDoesNotReapplyChangesThatWereAlreadyWritten() throws IOException {
        ServerRegistry first = ServerRegistry.load(configDir);
        first.addOrUpdate(ServerEntry.of("srv", 4701));
        first.save();

        ServerRegistry second = ServerRegistry.load(configDir);
        assertTrue(second.remove("srv", 4701));
        second.save();

        first.save();
        assertTrue(ServerRegistry.load(configDir).entries().isEmpty());
    }

    @Test
    void saveOverwritesExistingFileCompletely() throws IOException {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("first.example.com", 4701));
        registry.addOrUpdate(ServerEntry.of("second.example.com", 4701));
        registry.save();

        registry.remove("first.example.com", 4701);
        registry.save();

        assertFalse(rawJson().contains("first.example.com"));
        assertEquals(1, ServerRegistry.load(configDir).entries().size());
    }

    @Test
    void toleratesUnknownFields() throws IOException {
        writeFile("""
                {
                  "schemaVersion": 7,
                  "servers": [ { "address": "srv", "port": 4701, "colour": "blue" } ]
                }
                """);

        ServerRegistry registry = ServerRegistry.load(configDir);
        assertTrue(registry.loadWarning().isEmpty());
        assertEquals(1, registry.entries().size());
        assertEquals("srv", registry.entries().get(0).address());
    }

    @Test
    void treatsMissingServersArrayAsEmpty() throws IOException {
        writeFile("{}");
        ServerRegistry registry = ServerRegistry.load(configDir);
        assertTrue(registry.entries().isEmpty());
        assertTrue(registry.loadWarning().isEmpty());
    }

    @Test
    void movesFileHoldingJsonNullAside() throws IOException {
        writeFile("null");

        ServerRegistry registry = ServerRegistry.load(configDir);

        assertTrue(registry.entries().isEmpty());
        assertTrue(registry.loadWarning().isPresent());
        assertTrue(Files.exists(configDir.resolve(ServerRegistry.FILE_NAME + ServerRegistry.BROKEN_SUFFIX)));
    }

    @Test
    void skipsNullEntriesInTheServerList() throws IOException {
        writeFile("{ \"servers\": [ null, { \"address\": \"srv\", \"port\": 4701 }, null ] }");

        ServerRegistry registry = ServerRegistry.load(configDir);

        assertEquals(List.of(ServerEntry.of("srv", 4701)), registry.entries());
        assertTrue(registry.loadWarning().isEmpty());
    }

    @Test
    void movesCorruptFileAsideAndStartsEmpty() throws IOException {
        writeFile("{ this is not json");

        ServerRegistry registry = ServerRegistry.load(configDir);
        assertTrue(registry.entries().isEmpty());
        assertTrue(registry.loadWarning().isPresent());

        Path broken = configDir.resolve(ServerRegistry.FILE_NAME + ServerRegistry.BROKEN_SUFFIX);
        assertTrue(Files.exists(broken));
        assertEquals("{ this is not json", Files.readString(broken));
        assertFalse(Files.exists(file()));
    }

    @Test
    void movesFileWithInvalidEntryAside() throws IOException {
        writeFile("{ \"servers\": [ { \"address\": \"\", \"port\": 4701 } ] }");

        ServerRegistry registry = ServerRegistry.load(configDir);
        assertTrue(registry.entries().isEmpty());
        assertTrue(registry.loadWarning().isPresent());
        assertTrue(Files.exists(configDir.resolve(ServerRegistry.FILE_NAME + ServerRegistry.BROKEN_SUFFIX)));
    }

    @Test
    void corruptFileCanBeReplacedBySave() throws IOException {
        writeFile("garbage");
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("srv", 4701));
        registry.save();

        ServerRegistry reloaded = ServerRegistry.load(configDir);
        assertTrue(reloaded.loadWarning().isEmpty());
        assertEquals(1, reloaded.entries().size());
    }

    @Test
    void roundTripsLastUsed() throws IOException {
        Instant used = Instant.parse("2026-07-24T10:15:30Z");
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(ServerEntry.of("srv", 4701).withLastUsed(used));
        registry.save();

        assertTrue(rawJson().contains("2026-07-24T10:15:30Z"), rawJson());
        assertEquals(used.toString(), ServerRegistry.load(configDir).find("srv", 4701).orElseThrow().lastUsed());
    }

    @Test
    void readsFileWrittenBeforeLastUsedExisted() throws IOException {
        writeFile("{ \"servers\": [ { \"address\": \"srv\", \"port\": 4701, \"lastLogin\": \"admin\" } ] }");

        ServerRegistry registry = ServerRegistry.load(configDir);
        ServerEntry entry = registry.find("srv", 4701).orElseThrow();
        assertTrue(registry.loadWarning().isEmpty());
        assertEquals("admin", entry.lastLogin());
        assertNull(entry.lastUsed());
    }

    @Test
    void saveKeepsAnotherInstancesLastUsed() throws IOException {
        Instant used = Instant.parse("2026-07-24T10:15:30Z");
        ServerRegistry first = ServerRegistry.load(configDir);
        first.addOrUpdate(ServerEntry.of("shared", 4701));
        first.save();

        ServerRegistry second = ServerRegistry.load(configDir);

        first.addOrUpdate(ServerEntry.of("shared", 4701).withLastUsed(used));
        first.save();

        second.addOrUpdate(ServerEntry.of("other", 4701));
        second.save();

        assertEquals(used.toString(), ServerRegistry.load(configDir).find("shared", 4701).orElseThrow().lastUsed());
    }

    @Test
    void serializedFileNeverContainsCredentialFields() throws IOException {
        ServerRegistry registry = ServerRegistry.load(configDir);
        registry.addOrUpdate(new ServerEntry("srv1.example.com", 4701, "admin", "5.2.3", null).withLastUsed(Instant.now()));
        registry.addOrUpdate(new ServerEntry("srv2.example.com", 4702, "operator", "6.0.1", null));
        registry.save();

        String json = rawJson().toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("password", "token", "secret", "credential"))
            assertFalse(json.contains(forbidden), "servers.json must not contain '" + forbidden + "': " + json);
    }

    @Test
    void entryRejectsEmptyAddressAndOutOfRangePort() {
        assertThrows(NullPointerException.class, () -> ServerEntry.of(null, 4701));
        assertThrows(IllegalArgumentException.class, () -> ServerEntry.of("   ", 4701));
        assertThrows(IllegalArgumentException.class, () -> ServerEntry.of("srv", 70000));
    }

    @Test
    void entryNormalizesAddressAndPort() {
        ServerEntry entry = ServerEntry.of("  srv  ", 0);
        assertEquals("srv", entry.address());
        assertEquals(ServerEntry.DEFAULT_PORT, entry.port());
        assertEquals("srv:4701", entry.displayAddress());
        assertTrue(entry.matches("SRV", ServerEntry.DEFAULT_PORT));
        assertFalse(entry.matches(null, ServerEntry.DEFAULT_PORT));
    }

    @Test
    void withersPreserveIdentityFields() {
        ServerEntry entry = ServerEntry.of("srv", 4701).withLastLogin("admin").withLastSeenVersion("5.2.3");
        assertEquals("srv", entry.address());
        assertEquals(4701, entry.port());
        assertEquals("admin", entry.lastLogin());
        assertEquals("5.2.3", entry.lastSeenVersion());
    }

    @Test
    void withersThreadEveryOtherFieldThrough() {
        Instant used = Instant.parse("2026-07-24T10:15:30Z");
        ServerEntry stamped = ServerEntry.of("srv", 4701).withLastUsed(used);

        assertEquals(used.toString(), stamped.withLastLogin("admin").lastUsed());
        assertEquals(used.toString(), stamped.withLastSeenVersion("5.2.3").lastUsed());

        ServerEntry known = new ServerEntry("srv", 4701, "admin", "5.2.3", null);
        ServerEntry refreshed = known.withLastUsed(used);
        assertEquals("admin", refreshed.lastLogin());
        assertEquals("5.2.3", refreshed.lastSeenVersion());
        assertNull(refreshed.withLastUsed(null).lastUsed());
    }
}
