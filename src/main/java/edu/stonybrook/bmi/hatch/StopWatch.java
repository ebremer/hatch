package edu.stonybrook.bmi.hatch;

/**
 *
 * @author erich
 */
public final class StopWatch {
    private final long start;

    public StopWatch() {
        start = System.nanoTime();
    }

    /** Seconds since the watch was created, to two decimals. */
    public String seconds() {
        return String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - start) / 1e9);
    }
}
