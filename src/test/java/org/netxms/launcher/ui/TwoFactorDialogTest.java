package org.netxms.launcher.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The only part of the method dialog reachable without a display.
 */
class TwoFactorDialogTest {
    @Test
    void serverOfferingNoMethodCancelsTheExchange() {
        assertEquals(TwoFactorDialog.CANCELLED, TwoFactorDialog.selectMethod(null, null));
        assertEquals(TwoFactorDialog.CANCELLED, TwoFactorDialog.selectMethod(null, List.of()));
    }

    @Test
    void singleMethodIsSelectedWithoutAskingTheUser() {
        assertEquals(0, TwoFactorDialog.selectMethod(null, List.of("TOTP")));
    }
}
