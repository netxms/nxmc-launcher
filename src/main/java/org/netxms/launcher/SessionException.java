package org.netxms.launcher;

public class SessionException extends Exception {
    private final Kind kind;

    public SessionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SessionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        UNREACHABLE,
        BAD_CREDENTIALS,
        BAD_TWO_FACTOR_CODE,
        PASSWORD_REJECTED,
        NO_GRACE_LOGINS,
        CANCELLED,
        PROTOCOL_FAILURE
    }
}
