package org.netxms.launcher;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.Optional;

public record CheckStamp(long moment, boolean armed) {
    public static final CheckStamp NEVER = new CheckStamp(0L, false);

    private static final long ARM_BASE = -1L;

    @JsonCreator
    static CheckStamp of(long stored) {
        return (stored < 0) ? new CheckStamp(ARM_BASE - stored, true) : new CheckStamp(stored, false);
    }

    @JsonValue
    long stored() {
        return armed ? (ARM_BASE - moment) : moment;
    }

    public Optional<Instant> checkedAt() {
        return (!armed && (moment > NEVER.moment)) ? Optional.of(Instant.ofEpochMilli(moment)) : Optional.empty();
    }

    CheckStamp check(long now) {
        return new CheckStamp(Math.max(now, moment), false);
    }

    CheckStamp arm(long now) {
        return new CheckStamp(Math.max(now, moment + 1), true);
    }

    CheckStamp installed(long now) {
        return armed ? this : check(now);
    }

    CheckStamp orLater(CheckStamp other) {
        if (moment != other.moment) {
            return (moment > other.moment) ? this : other;
        }
        return armed ? other : this;
    }
}
