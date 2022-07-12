package edu.stonybrook.bmi.hatch;

/**
 *
 * @author erich
 */
public final class StopWatch {
    private long start;
    private long stop;
    private long last;
    private long lastlap;
    private long cumulative;
    
    public StopWatch() {
        reset();
    }
    
    public void reset() {
        cumulative = 0;
        start = System.nanoTime();
        last = start;
        stop = start;
        lastlap = start;
    }   
    
    public void stop() {
        stop = System.nanoTime();
    }    

    public void Lapse() {
        long now = (System.nanoTime()-start)/1000000000L;
        System.out.println(now+" seconds");
    }
    
    public void Lap() {
        long now =  System.nanoTime();
        cumulative = cumulative + now - lastlap;
        now = cumulative /1000000000L;
        lastlap = now;
        System.out.println(now+" seconds");
    }
    
    public void Cumulative() {
        long now =  System.nanoTime();
        cumulative = cumulative + now - last;
        last = now;
        now = cumulative / 1000000000L;
        System.out.println("Cumulative : "+now+" seconds");
    }
}
