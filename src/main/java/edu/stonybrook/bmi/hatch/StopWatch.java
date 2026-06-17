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

    public void Cumulative() {
        double diff = System.nanoTime() - start;
        diff = diff / 1000000000L;
        System.out.println("Cumulative : " + diff + " seconds");
    }
}
