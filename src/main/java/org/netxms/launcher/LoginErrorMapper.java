package org.netxms.launcher;

import org.netxms.client.NXCException;
import org.netxms.client.constants.RCC;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class LoginErrorMapper {
    private LoginErrorMapper() {
    }

    public static SessionException map(Throwable e) {
        if (e instanceof SessionException) {
            return (SessionException) e;
        }

        if (e instanceof NXCException) {
            return fromRcc((NXCException) e);
        }

        if (e instanceof UnknownHostException) {
            return new SessionException(SessionException.Kind.UNREACHABLE, "Unknown host: " + e.getMessage(), e);
        }

        if (e instanceof SocketTimeoutException) {
            return new SessionException(SessionException.Kind.UNREACHABLE, "Connection to server timed out", e);
        }

        if (e instanceof IOException) {
            return new SessionException(SessionException.Kind.UNREACHABLE, "Cannot communicate with server: " + describe(e), e);
        }

        return new SessionException(SessionException.Kind.PROTOCOL_FAILURE, describe(e), e);
    }

    private static SessionException fromRcc(NXCException e) {
        SessionException.Kind kind = switch (e.getErrorCode()) {
            case RCC.ACCESS_DENIED -> SessionException.Kind.BAD_CREDENTIALS;
            case RCC.FAILED_2FA_VALIDATION -> SessionException.Kind.BAD_TWO_FACTOR_CODE;
            case RCC.WEAK_PASSWORD, RCC.REUSED_PASSWORD -> SessionException.Kind.PASSWORD_REJECTED;
            case RCC.NO_GRACE_LOGINS -> SessionException.Kind.NO_GRACE_LOGINS;
            case RCC.OPERATION_CANCELLED -> SessionException.Kind.CANCELLED;
            case RCC.TIMEOUT, RCC.COMM_FAILURE, RCC.CANNOT_RESOLVE_HOSTNAME -> SessionException.Kind.UNREACHABLE;
            default -> SessionException.Kind.PROTOCOL_FAILURE;
        };
        return new SessionException(kind, describe(e), e);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return ((message != null) && !message.trim().isEmpty()) ? message : e.getClass().getSimpleName();
    }
}
