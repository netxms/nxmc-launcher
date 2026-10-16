package org.netxms.launcher.ui;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadEtaTest {
    private static final long MB = 1024L * 1024L;

    private final TestClock clock = new TestClock(Instant.parse("2026-07-25T10:00:00Z"));
    private final DownloadEta eta = new DownloadEta(clock);

    private static long parse(String text) {
        assertTrue(text.startsWith("about ") && text.endsWith(" left"), "unexpected wording: " + text);
        String[] parts = text.substring("about ".length(), text.length() - " left".length()).split(":");
        assertTrue((parts.length == 2) || (parts.length == 3), "unexpected wording: " + text);
        long seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                assertEquals(2, parts[i].length(), "field " + i + " is not zero padded: " + text);
            }
            seconds = (seconds * 60) + Long.parseLong(parts[i]);
        }
        return seconds;
    }

    @Test
    void nothingIsEstimatedBeforeAnySample() {
        assertEquals(Optional.empty(), eta.remaining());
    }

    @Test
    void nothingIsEstimatedBeforeTheTrustThreshold() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofMillis(2900));
        eta.sample(3 * MB, 100 * MB);

        assertEquals(Optional.empty(), eta.remaining());
    }

    @Test
    void theTrustThresholdItselfIsEnoughToEstimateFrom() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(3));
        eta.sample(3 * MB, 100 * MB);

        assertEquals(Optional.of("about 1:35 left"), eta.remaining());
    }

    @Test
    void anHourOrMoreIsCountedInHours() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(40));
        eta.sample(MB, 100 * MB);

        assertEquals(Optional.of("about 1:06:00 left"), eta.remaining());
    }

    @Test
    void justUnderAnHourStaysInMinutes() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(35));
        eta.sample(MB, 100 * MB);

        assertEquals(Optional.of("about 57:45 left"), eta.remaining());
    }

    @Test
    void aSteadyTransferIsEstimatedFromItsAverageRate() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(10));
        eta.sample(10 * MB, 100 * MB);

        assertEquals(Optional.of("about 1:30 left"), eta.remaining());
    }

    @Test
    void theRateIgnoresBytesTheFirstSampleAlreadyCarried() {
        eta.sample(20 * MB, 120 * MB);
        clock.advance(Duration.ofSeconds(10));
        eta.sample(30 * MB, 120 * MB);

        assertEquals(Optional.of("about 1:30 left"), eta.remaining());
    }

    @Test
    void theRemainingTimeIsRoundedToFiveSeconds() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(23));
        eta.sample(23 * MB, 100 * MB);

        assertEquals(Optional.of("about 1:15 left"), eta.remaining());
    }

    @Test
    void theCountdownNeverGrowsUnderAFixedRate() {
        eta.sample(0, 100 * MB);

        long previous = Long.MAX_VALUE;
        boolean seenNumber = false;
        for (int second = 1; second <= 95; second++) {
            clock.advance(Duration.ofSeconds(1));
            eta.sample(second * MB, 100 * MB);

            Optional<String> remaining = eta.remaining();
            if (remaining.isEmpty() || remaining.get().equals("almost done")) {
                continue;
            }

            long seconds = parse(remaining.get());
            assertTrue(seconds <= previous, "countdown grew at " + second + "s: " + seconds + " > " + previous);
            previous = seconds;
            seenNumber = true;
        }
        assertTrue(seenNumber, "the transfer never produced a numeric estimate");
    }

    @Test
    void theEndOfATransferIsAlmostDoneRatherThanFalsePrecision() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(92));
        eta.sample(92 * MB, 100 * MB);

        assertEquals(Optional.of("almost done"), eta.remaining());
    }

    @Test
    void aCompletedTransferIsAlmostDoneRatherThanZero() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(100));
        eta.sample(100 * MB, 100 * MB);

        assertEquals(Optional.of("almost done"), eta.remaining());
    }

    @Test
    void anUnpublishedTotalIsNeverEstimated() {
        eta.sample(0, 0);
        clock.advance(Duration.ofSeconds(30));
        eta.sample(30 * MB, 0);

        assertEquals(Optional.empty(), eta.remaining());
    }

    @Test
    void aTransferThatMadeNoProgressIsNeverEstimated() {
        eta.sample(2 * MB, 100 * MB);
        clock.advance(Duration.ofSeconds(30));
        eta.sample(2 * MB, 100 * MB);

        assertEquals(Optional.empty(), eta.remaining());
    }

    @Test
    void aClockThatDidNotAdvanceIsNeverEstimated() {
        eta.sample(0, 100 * MB);
        eta.sample(10 * MB, 100 * MB);

        assertEquals(Optional.empty(), eta.remaining());
    }

    @Test
    void aTruncatedProgressReportIsNotEstimatedNegatively() {
        eta.sample(0, 100 * MB);
        clock.advance(Duration.ofSeconds(10));
        eta.sample(120 * MB, 100 * MB);

        assertEquals(Optional.of("almost done"), eta.remaining());
    }

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
