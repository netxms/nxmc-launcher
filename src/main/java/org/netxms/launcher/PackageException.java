package org.netxms.launcher;


public class PackageException extends Exception {
    private final Kind kind;

    public PackageException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public PackageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        DOWNLOAD_FAILED,
        HASH_MISMATCH,
        LOCKED_BY_ANOTHER_LAUNCHER,
        CANCELLED,
        INVALID_PACKAGE,
        CACHE_ERROR
    }
}
