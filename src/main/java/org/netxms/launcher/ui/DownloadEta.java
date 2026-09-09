package org.netxms.launcher.ui;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class DownloadEta {
    private static final Duration MIN_ELAPSED = Duration.ofSeconds(3);
    private static final long ALMOST_DONE_SECONDS = 10;
    private static final long ROUNDING_SECONDS = 5;
    private static final long HOUR_SECONDS = 3600;

    private final Clock clock;

    private Instant start;
    private long startBytes;
    private Instant last;
    private long downloaded;
    private long total;

    public DownloadEta() {
        this(Clock.systemUTC());
    }

    DownloadEta(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void sample(long downloaded, long total) {
        Instant now = clock.instant();
        if (start == null) {
            start = now;
            startBytes = Math.max(downloaded, 0);
        }
        this.last = now;
        this.downloaded = Math.max(downloaded, 0);
        this.total = total;
    }

    public Optional<String> remaining() {
        if ((start == null) || (total <= 0)) {
            return Optional.empty();
        }

        long progressed = downloaded - startBytes;
        Duration elapsed = Duration.between(start, last);
        if ((progressed <= 0) || (elapsed.compareTo(MIN_ELAPSED) < 0)) {
            return Optional.empty();
        }

        double rate = progressed / (elapsed.toMillis() / 1000.0);
        long left = Math.max(total - downloaded, 0);
        long seconds = Math.round(left / rate);

        long rounded = Math.round(seconds / (double) ROUNDING_SECONDS) * ROUNDING_SECONDS;
        if (rounded <= ALMOST_DONE_SECONDS) {
            return Optional.of("almost done");
        }
        if (rounded >= HOUR_SECONDS) {
            return Optional.of(String.format(Locale.ROOT, "about %d:%02d:%02d left", rounded / HOUR_SECONDS, (rounded % HOUR_SECONDS) / 60, rounded % 60));
        }
        return Optional.of(String.format(Locale.ROOT, "about %d:%02d left", rounded / 60, rounded % 60));
    }
}
