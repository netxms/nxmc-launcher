package org.netxms.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LauncherSettingsTest {
    @TempDir
    Path configDir;

    private Path file() {
        return configDir.resolve(LauncherSettings.FILE_NAME);
    }

    private void writeFile(String content) throws IOException {
        Files.writeString(file(), content, StandardCharsets.UTF_8);
    }

    @Test
    void firstRunReadsAsUnsetWithoutCreatingFile() {
        LauncherSettings settings = LauncherSettings.load(configDir.resolve("missing"));
        assertTrue(settings.checkForUpdates().isEmpty());
        assertFalse(Files.exists(settings.file()));
        assertEquals(LauncherSettings.FILE_NAME, settings.file().getFileName().toString());
    }

    @Test
    void roundTripsBothAnswers() throws IOException {
        LauncherSettings settings = LauncherSettings.load(configDir);
        assertTrue(settings.checkForUpdates().isEmpty());

        settings.setCheckForUpdates(true);
        settings.save();
        assertEquals(Boolean.TRUE, LauncherSettings.load(configDir).checkForUpdates().orElseThrow());

        settings.setCheckForUpdates(false);
        settings.save();
        assertEquals(Boolean.FALSE, LauncherSettings.load(configDir).checkForUpdates().orElseThrow());
    }

    @Test
    void savingUnsetSettingsKeepsThemUnset() throws IOException {
        LauncherSettings settings = LauncherSettings.load(configDir);
        settings.save();

        assertTrue(Files.isRegularFile(file()));
        assertTrue(LauncherSettings.load(configDir).checkForUpdates().isEmpty());
    }

    @Test
    void saveCreatesMissingDirectoriesAndLeavesNoTempFiles() throws IOException {
        Path nested = configDir.resolve("deep/config");
        LauncherSettings settings = LauncherSettings.load(nested);
        settings.setCheckForUpdates(true);
        settings.save();

        try (var files = Files.list(nested)) {
            assertEquals(List.of(LauncherSettings.FILE_NAME),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void toleratesUnknownFields() throws IOException {
        writeFile("{ \"schemaVersion\": 7, \"checkForUpdates\": true, \"colour\": \"blue\" }");

        assertEquals(Boolean.TRUE, LauncherSettings.load(configDir).checkForUpdates().orElseThrow());
    }

    @Test
    void documentWithoutTheFieldReadsAsUnset() throws IOException {
        writeFile("{}");

        assertTrue(LauncherSettings.load(configDir).checkForUpdates().isEmpty());
    }

    @Test
    void fileHoldingJsonNullReadsAsUnset() throws IOException {
        writeFile("null");

        assertTrue(LauncherSettings.load(configDir).checkForUpdates().isEmpty());
    }

    @Test
    void malformedFileReadsAsUnsetAndIsOverwrittenByTheNextSave() throws IOException {
        writeFile("{ this is not json");

        LauncherSettings settings = LauncherSettings.load(configDir);
        assertTrue(settings.checkForUpdates().isEmpty());

        settings.setCheckForUpdates(true);
        settings.save();

        assertEquals(Boolean.TRUE, LauncherSettings.load(configDir).checkForUpdates().orElseThrow());
        assertFalse(Files.readString(file(), StandardCharsets.UTF_8).contains("not json"));
    }

    @Test
    void fieldOfTheWrongTypeReadsAsUnset() throws IOException {
        writeFile("{ \"checkForUpdates\": \"whenever\" }");

        assertTrue(LauncherSettings.load(configDir).checkForUpdates().isEmpty());
    }

    @Test
    void directoryInPlaceOfTheFileReadsAsUnset() throws IOException {
        Files.createDirectory(file());

        assertTrue(LauncherSettings.load(configDir).checkForUpdates().isEmpty());
    }

    @Test
    void saveOnAnUnwritableDirectoryThrows() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");

        Path locked = Files.createDirectory(configDir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(locked), "the test user can write regardless of permissions");

            LauncherSettings settings = LauncherSettings.load(locked);
            settings.setCheckForUpdates(true);
            assertThrows(IOException.class, settings::save);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
