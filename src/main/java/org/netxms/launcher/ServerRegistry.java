package org.netxms.launcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ServerRegistry {
    static final String FILE_NAME = "servers.json";
    static final String BROKEN_SUFFIX = ".broken";
    static final String LOCK_SUFFIX = ".lock";

    private static final ObjectMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path file;
    private final List<ServerEntry> entries;
    private final List<Change> journal = new ArrayList<>();
    private final String loadWarning;

    private ServerRegistry(Path file, List<ServerEntry> entries, String loadWarning) {
        this.file = file;
        this.entries = new ArrayList<>(entries);
        this.loadWarning = loadWarning;
    }

    public static ServerRegistry load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            return new ServerRegistry(file, read(file), null);
        } catch (IOException e) {
            return new ServerRegistry(file, List.of(), moveAside(file, e));
        }
    }

    private static void apply(List<ServerEntry> target, Change change) {
        int index = indexOf(target, change.address(), change.port());
        if (change.entry() == null) {
            if (index >= 0) {
                target.remove(index);
            }
        } else if (index >= 0) {
            target.set(index, change.entry());
        } else {
            target.add(change.entry());
        }
    }

    private static List<ServerEntry> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }

        Document document;
        try {
            document = MAPPER.readValue(file.toFile(), Document.class);
        } catch (JacksonException e) {
            throw new IOException(e.getOriginalMessage(), e);
        }
        if (document == null) {
            throw new IOException("it holds no server list");
        }
        return (document.servers() != null) ? document.servers().stream().filter(Objects::nonNull).toList() : List.of();
    }

    private static int indexOf(List<ServerEntry> target, String address, int port) {
        for (int i = 0; i < target.size(); i++) {
            if (target.get(i).matches(address, port)) {
                return i;
            }
        }
        return -1;
    }

    private static String moveAside(Path file, IOException cause) {
        Path broken = file.resolveSibling(file.getFileName() + BROKEN_SUFFIX);
        try {
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            return "Server list " + file + " is unreadable (" + cause.getMessage() + "); it was moved to " + broken + " and an empty list is used.";
        } catch (IOException e) {
            return "Server list " + file + " is unreadable (" + cause.getMessage() + ") and could not be moved aside "
                    + "(" + e.getMessage() + "); an empty list is used.";
        }
    }

    public Path file() {
        return file;
    }

    public synchronized List<ServerEntry> entries() {
        return List.copyOf(entries);
    }

    public Optional<String> loadWarning() {
        return Optional.ofNullable(loadWarning);
    }

    public synchronized Optional<ServerEntry> find(String address, int port) {
        return entries.stream().filter(e -> e.matches(address, port)).findFirst();
    }

    public synchronized void addOrUpdate(ServerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Change change = new Change(entry.address(), entry.port(), entry);
        apply(entries, change);
        journal.add(change);
    }

    public synchronized boolean remove(String address, int port) {
        int index = indexOf(entries, address, port);
        if (index < 0) {
            return false;
        }

        entries.remove(index);
        journal.add(new Change(address, port, null));
        return true;
    }

    public void save() throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);

        try (FileChannel channel = FileChannel.open(dir.resolve(FILE_NAME + LOCK_SUFFIX), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            synchronized (this) {
                try (FileLock lock = channel.lock()) {
                    writeMerged();
                }
            }
        }
    }

    private void writeMerged() throws IOException {
        List<ServerEntry> merged = replayOnDisk();

        Path dir = file.getParent();
        Path temp = Files.createTempFile(dir, FILE_NAME, ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), new Document(merged));
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

        journal.clear();
        entries.clear();
        entries.addAll(merged);
    }

    private List<ServerEntry> replayOnDisk() {
        List<ServerEntry> base;
        try {
            base = read(file);
        } catch (IOException e) {
            base = entries;
        }

        List<ServerEntry> merged = new ArrayList<>(base);
        for (Change change : journal)
            apply(merged, change);
        return merged;
    }

    private record Change(String address, int port, ServerEntry entry) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(List<ServerEntry> servers) {
    }
}
