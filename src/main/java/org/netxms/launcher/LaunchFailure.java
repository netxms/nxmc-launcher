package org.netxms.launcher;

public class LaunchFailure extends Exception {
    public static final int NO_EXIT_CODE = -1;
    private final Kind kind;
    private final int exitCode;
    private final String stderrTail;

    public LaunchFailure(Kind kind, String message) {
        this(kind, message, NO_EXIT_CODE, "", null);
    }

    public LaunchFailure(Kind kind, String message, Throwable cause) {
        this(kind, message, NO_EXIT_CODE, "", cause);
    }

    public LaunchFailure(Kind kind, String message, int exitCode, String stderrTail, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.exitCode = exitCode;
        this.stderrTail = stderrTail == null ? "" : stderrTail;
    }

    public Kind kind() {
        return kind;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stderrTail() {
        return stderrTail;
    }

    public enum Kind {
        JAVA_NOT_FOUND,
        SPAWN_FAILED,
        EARLY_EXIT
    }
}
