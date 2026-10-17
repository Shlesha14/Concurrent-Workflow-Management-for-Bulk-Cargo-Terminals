package com.portflow.simulation;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Injectable simulation clock. Domain times (arrival, expiry, deadlines, task start/end) come from here, never from
 * LocalDateTime.now(), so tests are deterministic and demos can run hours of "port time" in seconds.
 * speed = simulated seconds per real second (1 = real time, 60 = one simulated minute per real second).
 * A fixed clock only moves with advance(). The speed can be changed at run time without time jumping.
 */
public final class SimClock {
    private record State(LocalDateTime base, long nanoBase, double speed, boolean fixed) {}
    private volatile State state;

    private SimClock(State s) { this.state = s; }

    public static SimClock realTime() { return new SimClock(new State(LocalDateTime.now(), System.nanoTime(), 1, false)); }
    public static SimClock scaled(LocalDateTime simStart, double speed) { return new SimClock(new State(simStart, System.nanoTime(), speed, false)); }
    public static SimClock fixed(LocalDateTime t) { return new SimClock(new State(t, System.nanoTime(), 0, true)); }

    public LocalDateTime now() {
        State s = state;
        if (s.fixed()) return s.base();
        return s.base().plusNanos((long) ((System.nanoTime() - s.nanoBase()) * s.speed()));
    }

    public synchronized void advance(Duration d) { State s = state; state = new State(s.base().plus(d), s.nanoBase(), s.speed(), s.fixed()); }

    /** Re-bases the clock so that now() is continuous across the speed change. */
    public synchronized void setSpeed(double speed) {
        State s = state;
        if (s.fixed()) return;
        state = new State(now(), System.nanoTime(), Math.max(1, speed), false);
    }

    public double speed() { State s = state; return s.fixed() ? 1 : s.speed(); }

    /** Real milliseconds a task of the given simulated minutes should take (at least 1 ms). */
    public long realMillis(long simMinutes) { return Math.max(1, (long) (simMinutes * 60_000.0 / speed())); }
    /** Simulated minutes elapsed between two instants (fractional). */
    public static double minutesBetween(LocalDateTime a, LocalDateTime b) { return Duration.between(a, b).toMillis() / 60000.0; }
}
