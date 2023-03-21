package edu.stonybrook.bmi.hatch;

import com.beust.jcommander.JCommander;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.jena.rdf.model.Model;

/**
 *
 * @author erich
 */
public class Hatch {    
    public static String software = "hatch 2.2.0 by Wing-n-Beak";
    private static final String[] ext = new String[] {".vsi", ".svs", ".tif"};
    public static final String HELP = Hatch.software+"\n"+
        """
        usage: hatch <src> <dest>
        -v : verbose
        """;
    
    private static void Traverse(HatchParameters params) {
        Path s = params.src.toPath();
        Path d = params.dest.toPath();
        ThreadPoolExecutor engine = new ThreadPoolExecutor(params.fp,params.fp,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
        engine.prestartAllCoreThreads();
        try (Stream<Path> X = Files.walk(params.src.toPath())) {
            X
                .filter(Objects::nonNull)
                .filter(fff -> {
                    for (String ext1 : ext) {
                        if (fff.toFile().toString().toLowerCase().endsWith(ext1)) {
                            return true;
                        }
                    } 
                    return false;
                })    
                .forEach(f->{
                    String frag = s.relativize(f).toString();
                    frag = frag.substring(0,frag.length()-4)+".tif";
                    Path t = Path.of(d.toString(), frag);
                    t.toFile().getParentFile().mkdirs();
                    if (!t.toFile().exists()) {
                        System.out.println("PROCESSING FILE --> "+f);
                        engine.submit(new FileProcessor(params, f.toString(), t.toString()));
                    } else if (t.toFile().length()==0) {
                        System.out.println("ZERO LENGTH FILE --> "+f);
                        t.toFile().delete();
                        System.out.println("RE-PROCESSING FILE --> "+f);
                        engine.submit(new FileProcessor(params, f.toString(), t.toString()));
                    }
                });
        } catch (IOException ex) {
            Logger.getLogger(Hatch.class.getName()).log(Level.SEVERE, null, ex);
        }
        int cc = engine.getActiveCount()+engine.getQueue().size();
        while ((engine.getActiveCount()+engine.getQueue().size())>0) {
            int curr = engine.getActiveCount()+engine.getQueue().size();
            if (cc!=curr) {
                cc=curr;
                if (params.verbose) System.out.println("All jobs submitted...waiting for "+curr);
            }
        }
        System.out.println("Engine shutdown");
        engine.shutdown();
    }
    
    public static void main(String[] args) {
        loci.common.DebugTools.setRootLevel("WARN");
        HatchParameters params = new HatchParameters();
        JCommander jc = JCommander.newBuilder().addObject(params).build();
        jc.setProgramName(Hatch.software+"\nhatch");    
        jc.parse(args);
        if (args.length==0) {
            jc.usage();
        } else {
            if (params.src.exists()) {
                if (params.src.isDirectory()) {
                    System.out.println(params.src+" is directory");
                    if (!params.dest.exists()) {
                        params.dest.mkdir();
                    }
                    if (!params.dest.isDirectory()) {
                        jc.usage();
                    } else {
                        Traverse(params);
                    }
                } else {
                    System.out.println(params.src+" is not a directory");
                    if (params.dest.exists()) {
                        System.out.println(params.dest+" does exist");
                        if (!params.dest.isDirectory()) {
                            System.out.println(params.dest+" is not a directory");
                            X2TIF v2t = new X2TIF(params, params.src.toString(), params.dest.toString());
                            v2t.Execute();
                            try {
                                v2t.close();
                            } catch (Exception ex) {
                                Logger.getLogger(Hatch.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        } else {
                            jc.usage();
                        }
                    } else {
                        System.out.println(params.dest+" does not exist");
                        X2TIF v2t = new X2TIF(params, params.src.toString(), params.dest.toString());
                        v2t.Execute();
                    }
                }
            } else {
                System.out.println(params.src.toString()+" does not exist!");
            }
        }
    }
}

class FileProcessor implements Callable<Model> {
    private final HatchParameters params;
    private final String src;
    private final String dest;

    public FileProcessor(HatchParameters params, String src, String dest) {
        this.params = params;
        this.src = src;
        this.dest = dest;
    }
    
    @Override
    public Model call() {
        try (X2TIF v2t = new X2TIF(params, src, dest)) {
            v2t.Execute();
        } catch (Exception ex) {
            System.out.println("FILE PROCESSOR ERROR --> "+src+" "+dest+" "+ex.toString());
        }
        return null;
    }    
}