package org.netxms.launcher;

public class ManifestException extends Exception {
    private final Kind kind;

    public ManifestException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ManifestException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        FETCH_FAILED,
        MALFORMED,
        UNTRUSTED_URL
    }
}
