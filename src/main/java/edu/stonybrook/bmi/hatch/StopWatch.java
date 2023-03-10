package edu.stonybrook.bmi.hatch;

/**
 *
 * @author erich
 */
public final class StopWatch {
    private final long start;
    private long lastlap;
    
    public StopWatch() {
        start = System.nanoTime();
        lastlap = start;
    }
    
    public void Lap() {
        long curr = System.nanoTime();
        long diff =  curr - lastlap;
        lastlap = curr;
        diff = diff / 1000000000L;
        System.out.println(diff+" seconds");
    }
    
    public void Cumulative() {
        double diff =  System.nanoTime() - start;
        diff = diff / 1000000000L;
        System.out.println("Cumulative : "+diff+" seconds");
    }
}
