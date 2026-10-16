package org.netxms.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.netxms.client.NXCException;
import org.netxms.client.constants.RCC;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class LoginErrorMapperTest {
    @ParameterizedTest
    @CsvSource({
            "2,   BAD_CREDENTIALS",
            // ACCESS_DENIED
            "148, BAD_TWO_FACTOR_CODE",
            // FAILED_2FA_VALIDATION
            "87,  PASSWORD_REJECTED",
            // WEAK_PASSWORD
            "88,  PASSWORD_REJECTED",
            // REUSED_PASSWORD
            "61,  NO_GRACE_LOGINS",
            // NO_GRACE_LOGINS
            "164, CANCELLED",
            // OPERATION_CANCELLED
            "4,   UNREACHABLE",
            // TIMEOUT
            "9,   UNREACHABLE",
            // COMM_FAILURE
            "1003, UNREACHABLE",
            // CANNOT_RESOLVE_HOSTNAME
            "31,  PROTOCOL_FAILURE",
            // VERSION_MISMATCH
            "149, PROTOCOL_FAILURE",
            // FAILED_2FA_PREPARATION
            "60,  PROTOCOL_FAILURE",
            // ACCOUNT_DISABLED
            "187, PROTOCOL_FAILURE"
            // NEED_2FA_SETUP
    })
    void mapsServerErrorCodes(int rcc, SessionException.Kind expected) {
        NXCException source = new NXCException(rcc);
        SessionException mapped = LoginErrorMapper.map(source);
        assertEquals(expected, mapped.kind());
        assertSame(source, mapped.getCause());
    }

    @ParameterizedTest
    @ValueSource(ints = {
            3,
            46,
            1002,
            30000
    })
    void unknownServerErrorCodesAreProtocolFailures(int rcc) {
        assertEquals(SessionException.Kind.PROTOCOL_FAILURE, LoginErrorMapper.map(new NXCException(rcc)).kind());
    }

    @Test
    void keepsServerErrorText() {
        SessionException mapped = LoginErrorMapper.map(new NXCException(RCC.ACCESS_DENIED));
        assertNotNull(mapped.getMessage());
        assertFalse(mapped.getMessage().trim().isEmpty());
    }

    @Test
    void mapsUnknownHostToUnreachable() {
        SessionException mapped = LoginErrorMapper.map(new UnknownHostException("nosuch.example.com"));
        assertEquals(SessionException.Kind.UNREACHABLE, mapped.kind());
        assertTrue(mapped.getMessage().contains("nosuch.example.com"));
    }

    @Test
    void mapsSocketTimeoutToUnreachable() {
        assertEquals(SessionException.Kind.UNREACHABLE, LoginErrorMapper.map(new SocketTimeoutException("connect " +
                "timed out")).kind());
    }

    @Test
    void mapsGenericIoErrorToUnreachable() {
        SessionException mapped = LoginErrorMapper.map(new IOException("connection reset"));
        assertEquals(SessionException.Kind.UNREACHABLE, mapped.kind());
        assertTrue(mapped.getMessage().contains("connection reset"));
    }

    @Test
    void usesExceptionClassNameWhenMessageIsMissing() {
        assertTrue(LoginErrorMapper.map(new EOFException()).getMessage().contains("EOFException"));
    }

    @Test
    void mapsUnexpectedRuntimeFailureToProtocolFailure() {
        SessionException mapped = LoginErrorMapper.map(new IllegalStateException("session already disconnected"));
        assertEquals(SessionException.Kind.PROTOCOL_FAILURE, mapped.kind());
        assertEquals("session already disconnected", mapped.getMessage());
    }

    @Test
    void passesThroughAlreadyMappedFailure() {
        SessionException original = new SessionException(SessionException.Kind.CANCELLED, "cancelled by user");
        assertSame(original, LoginErrorMapper.map(original));
    }
}
