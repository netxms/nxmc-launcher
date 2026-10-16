package org.netxms.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerVersionTest {
    private static ServerVersion version(String s) {
        return ServerVersion.parse(s).orElseThrow();
    }

    @ParameterizedTest
    @CsvSource({
            "5.2,           5, 2, 0, 0, 5.2",
            "5.2.3,         5, 2, 3, 0, 5.2",
            "5.2.3.456,     5, 2, 3, 456, 5.2",
            "5.2.3-rc1,     5, 2, 3, 0, 5.2",
            "5.2-SNAPSHOT,  5, 2, 0, 0, 5.2",
            "5.2.3+build7,  5, 2, 3, 0, 5.2",
            "'  5.2.3  ',   5, 2, 3, 0, 5.2",
            "10.0.12,       10, 0, 12, 0, 10.0",
            "0.0.0,         0, 0, 0, 0, 0.0"
    })
    void parsesSupportedFormats(String input, int major, int minor, int patch, int build, String branchKey) {
        ServerVersion v = ServerVersion.parse(input).orElseThrow();
        assertEquals(major, v.major());
        assertEquals(minor, v.minor());
        assertEquals(patch, v.patch());
        assertEquals(build, v.build());
        assertEquals(branchKey, v.branchKey());
    }

    @Test
    void keepsOriginalStringAsFull() {
        assertEquals("5.2.3-rc1", ServerVersion.parse("  5.2.3-rc1  ").orElseThrow().full());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "5",
            "5.",
            "5.2.",
            ".5.2",
            "5.2.3.4.5",
            "five.two",
            "5.x",
            "v5.2",
            "-1.2",
            "5..2",
            "5.2.3a",
            "UNKNOWN",
            "custom-build",
            "5 2 3",
            "9999999999.1"
    })
    void rejectsUnparseableInput(String input) {
        assertEquals(Optional.empty(), ServerVersion.parse(input));
    }

    @Test
    void detectsNewerPatchWithinBranch() {
        assertTrue(version("5.2.4").isNewerPatchThan(version("5.2.3")));
        assertFalse(version("5.2.3").isNewerPatchThan(version("5.2.4")));
        assertFalse(version("5.2.3").isNewerPatchThan(version("5.2.3")));
    }

    @Test
    void comparesBuildNumberWhenPatchIsEqual() {
        assertTrue(version("5.2.3.457").isNewerPatchThan(version("5.2.3.456")));
        assertFalse(version("5.2.3.456").isNewerPatchThan(version("5.2.3.457")));
        assertTrue(version("5.2.4.1").isNewerPatchThan(version("5.2.3.999")));
    }

    @Test
    void neverComparesAcrossBranches() {
        assertFalse(version("5.3.0").isNewerPatchThan(version("5.2.9")));
        assertFalse(version("6.0.0").isNewerPatchThan(version("5.2.9")));
        assertFalse(version("5.2.9").isNewerPatchThan(version("5.3.0")));
    }

    @Test
    void ignoresQualifiersWhenComparing() {
        assertFalse(version("5.2.3-rc1").isNewerPatchThan(version("5.2.3")));
        assertFalse(version("5.2.3").isNewerPatchThan(version("5.2.3-rc1")));
        assertTrue(version("5.2.4-rc1").isNewerPatchThan(version("5.2.3")));
    }

    @Test
    void placesTheFloorAtTheMajorAlone() {
        assertFalse(version("4.9.9").isSupported());
        assertTrue(version("5.0.0").isSupported());
        assertTrue(version("5.0.0-rc1").isSupported());
        assertTrue(version("6.2.1").isSupported());
    }
}
