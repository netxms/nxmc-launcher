package org.netxms.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ServerEntryTest {
    @Test
    void aHostnameOnTheDefaultPortIsHandedOverWithoutAPort() {
        assertEquals("srv", ServerEntry.compactAuthority("srv", ServerEntry.DEFAULT_PORT));
    }

    @Test
    void aHostnameOnAnotherPortCarriesIt() {
        assertEquals("srv:1234", ServerEntry.compactAuthority("srv", 1234));
    }

    @Test
    void anIpv6LiteralOnTheDefaultPortIsBracketedWithoutAPort() {
        assertEquals("[::1]", ServerEntry.compactAuthority("::1", ServerEntry.DEFAULT_PORT));
        assertEquals("[fe80::1]", ServerEntry.compactAuthority("fe80::1", ServerEntry.DEFAULT_PORT));
    }

    @Test
    void anIpv6LiteralOnAnotherPortIsBracketed() {
        assertEquals("[::1]:1234", ServerEntry.compactAuthority("::1", 1234));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db8::",
            "1:2::",
            "fe80:1::",
            "::1",
            "::",
            "1::",
            "2001:db8::1",
            "1:2:3:4:5:6:7:8"
    })
    void neverHandsAnIpv6LiteralOverInAFormNxmcReadsAsHostAndPort(String literal) {
        String handedOver = ServerEntry.compactAuthority(literal, ServerEntry.DEFAULT_PORT);
        assertEquals("[" + literal + "]", handedOver);
        assertNotEquals(2, handedOver.split(":").length, handedOver + " splits into two parts");
    }

    @Test
    void displayAddressAlwaysCarriesThePort() {
        assertEquals("srv:4701", ServerEntry.of("srv", ServerEntry.DEFAULT_PORT).displayAddress());
        assertEquals("srv:1234", ServerEntry.of("srv", 1234).displayAddress());
    }

    @Test
    void displayAddressBracketsAnIpv6Entry() {
        assertEquals("[::1]:4701", ServerEntry.of("::1", ServerEntry.DEFAULT_PORT).displayAddress());
        assertEquals("[2001:db8::1]:1234", ServerEntry.of("2001:db8::1", 1234).displayAddress());
    }

    @Test
    void aBracketedAddressIsStoredUnbracketed() {
        assertEquals("::1", ServerEntry.of("[::1]", ServerEntry.DEFAULT_PORT).address());
        assertEquals("[::1]:4701", ServerEntry.of("[::1]", ServerEntry.DEFAULT_PORT).displayAddress());
        assertEquals("::1", ServerEntry.of("  [::1]  ", 1234).address());
    }

    @Test
    void aBracketedAddressReadFromDiskIsStoredUnbracketedToo() {
        ServerEntry entry = new ServerEntry("[::1]", ServerEntry.DEFAULT_PORT, "admin", "5.2.1", null);
        assertEquals("::1", entry.address());
        assertTrue(entry.matches("::1", ServerEntry.DEFAULT_PORT));
    }

    @Test
    void bracketsHoldingNothingAreNoAddressAtAll() {
        assertThrows(IllegalArgumentException.class, () -> ServerEntry.of("[]", ServerEntry.DEFAULT_PORT));
        assertThrows(IllegalArgumentException.class, () -> ServerEntry.of("[  ]", ServerEntry.DEFAULT_PORT));
    }

    @Test
    void anUnpairedBracketIsLeftAlone() {
        assertEquals("[::1", ServerEntry.of("[::1", ServerEntry.DEFAULT_PORT).address());
    }
}
