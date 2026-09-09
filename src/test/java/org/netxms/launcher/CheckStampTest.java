package org.netxms.launcher;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CheckStampTest {
    @Test
    void aDescriptionWithNoNumberAtAllReadsAsNeverChecked() {
        assertEquals(CheckStamp.NEVER, CheckStamp.of(0L));
        assertEquals(0L, CheckStamp.NEVER.stored());
        assertFalse(CheckStamp.NEVER.armed());
        assertEquals(Optional.empty(), CheckStamp.NEVER.checkedAt());
    }

    @Test
    void aCheckIsStoredAsTheMomentItRan() {
        CheckStamp checked = CheckStamp.NEVER.check(1_700_000_000_000L);

        assertEquals(1_700_000_000_000L, checked.stored());
        assertEquals(checked, CheckStamp.of(checked.stored()));
        assertEquals(Optional.of(Instant.ofEpochMilli(1_700_000_000_000L)), checked.checkedAt());
    }

    @Test
    void anArmIsStoredAsANumberNoOlderDescriptionCanHold() {
        CheckStamp armed = CheckStamp.NEVER.arm(1_700_000_000_000L);

        assertTrue(armed.armed());
        assertTrue(armed.stored() < 0L, "a meta.json written before this field existed reads back as 0, never as " +
                "negative");
        assertEquals(armed, CheckStamp.of(armed.stored()));
    }

    @Test
    void anArmReportsNoCheckTime() {
        assertEquals(Optional.empty(), CheckStamp.NEVER.arm(1_700_000_000_000L).checkedAt());
    }

    @Test
    void twoArmsInsideOneMillisecondAreTwoValues() {
        CheckStamp first = CheckStamp.NEVER.arm(5L);
        CheckStamp second = first.arm(5L);

        assertNotEquals(first, second);
        assertTrue(second.moment() > first.moment());
    }

    @Test
    void anArmIsPastEveryStampTheBranchHasCarried() {
        CheckStamp armed = CheckStamp.NEVER.arm(5L);
        CheckStamp served = armed.check(5L);

        assertTrue(served.arm(5L).moment() > armed.moment(), "a re-arm after a disarm must not repeat the arm it " +
                "replaced");
    }

    @Test
    void aCheckNeverLandsBeforeTheStampItSucceeds() {
        CheckStamp armed = CheckStamp.NEVER.arm(1_000L);

        assertEquals(armed.moment(), armed.check(0L).moment(), "a clock that went backwards must not undo the " +
                "ordering");
    }

    @Test
    void anInstallKeepsAnArmAndChecksAnythingElse() {
        CheckStamp armed = CheckStamp.NEVER.arm(5L);
        assertEquals(armed, armed.installed(9L));

        assertEquals(CheckStamp.NEVER.check(9L), CheckStamp.NEVER.installed(9L));
    }

    @Test
    void theLaterOfTwoWritesIsTheOneWithTheHigherMoment() {
        CheckStamp early = CheckStamp.NEVER.check(5L);
        CheckStamp late = early.check(9L);

        assertEquals(late, early.orLater(late));
        assertEquals(late, late.orLater(early));
    }

    @Test
    void anArmAndTheCheckThatServedItInsideOneMillisecondOrderAsTheCheck() {
        CheckStamp armed = CheckStamp.NEVER.arm(5L);
        CheckStamp served = armed.check(5L);

        assertEquals(armed.moment(), served.moment(), "the clock separates none of it");
        assertEquals(served, armed.orLater(served));
        assertEquals(served, served.orLater(armed));
    }
}
