package org.netxms.launcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class LauncherSettings {
    static final String FILE_NAME = "settings.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path file;
    private Boolean checkForUpdates;

    private LauncherSettings(Path file, Boolean checkForUpdates) {
        this.file = file;
        this.checkForUpdates = checkForUpdates;
    }

    public static LauncherSettings load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        return new LauncherSettings(file, read(file));
    }

    private static Boolean read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try {
            Document document = MAPPER.readValue(file.toFile(), Document.class);
            return (document != null) ? document.checkForUpdates() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    public Path file() {
        return file;
    }

    public synchronized Optional<Boolean> checkForUpdates() {
        return Optional.ofNullable(checkForUpdates);
    }

    public synchronized void setCheckForUpdates(boolean value) {
        checkForUpdates = value;
    }

    public synchronized void save() throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);

        Path temp = Files.createTempFile(dir, FILE_NAME, ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), new Document(checkForUpdates));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (JacksonException e) {
            throw new IOException(e.getOriginalMessage(), e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Document(Boolean checkForUpdates) {
    }
}
