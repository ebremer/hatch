package edu.stonybrook.bmi.hatch;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 *
 * @author erich
 */
public class Hatch {
    public static String software = "hatch 4.3.0 by Wing-n-Beak";
    private static final String[] ext = new String[] {".vsi", ".svs", ".tif"};
    private static final Logger LOGGER;

    static {
         try {
             LogManager.getLogManager().readConfiguration(Hatch.class.getResourceAsStream("/logging.properties"));
         } catch (IOException | SecurityException | ExceptionInInitializerError ex) {
             Logger.getLogger(Hatch.class.getName()).log(Level.SEVERE, "Failed to read logging.properties file", ex);
         }
         LOGGER = Logger.getLogger(Hatch.class.getName());
     }

    public Hatch() {}

    private static void Traverse(HatchParameters params) {
        Path s;
        Path d;
        try {
            s = params.src.toPath().toRealPath();
            d = params.dest.toPath().toRealPath();
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0} {1} {2}", new Object[]{params.src.toString(), params.dest.toString(), ex.toString()});
            return;
        }
        if (d.startsWith(s)) {
            // outputs would land among the sources, where a .tif output can replace a .tif source
            LOGGER.log(Level.SEVERE, "Destination folder {0} must not be the source folder or inside it", d);
            return;
        }
        List<Path> inputs;
        try (Stream<Path> X = Files.walk(s)) {
            inputs = X
                .filter(Objects::nonNull)
                .filter(path->{
                    if (params.filter==null) {
                        return true;
                    }
                    return path.toString().contains(params.filter);
                })
                .filter(fff -> {
                    for (String ext1 : ext) {
                        if (fff.toFile().toString().toLowerCase().endsWith(ext1)) {
                            return true;
                        }
                    }
                    return false;
                })
                .toList();
        } catch (IOException | UncheckedIOException ex) {
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0} {1} {2}", new Object[]{params.src.toString(), params.dest.toString(), ex.toString()});
            return;
        }
        // Map every source to its output before anything is written, so that sources competing
        // for one output (a.svs + a.vsi -> a.tif) or an output landing on a source are refused.
        Map<Path, Path> jobs = new LinkedHashMap<>();
        Map<String, List<Path>> claims = new HashMap<>();
        Set<String> sources = new HashSet<>();
        for (Path f : inputs) {
            String frag = s.relativize(f).toString();
            frag = frag.substring(0,frag.length()-4)+".tif";
            Path t = d.resolve(frag);
            jobs.put(f, t);
            claims.computeIfAbsent(pathKey(t), k -> new ArrayList<>()).add(f);
            sources.add(pathKey(f));
        }
        ThreadPoolExecutor engine = new ThreadPoolExecutor(params.fp,params.fp,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
        engine.prestartAllCoreThreads();
        jobs.forEach((f, t) -> {
            List<Path> claimants = claims.get(pathKey(t));
            if (claimants.size() > 1) {
                LOGGER.log(Level.SEVERE, "Skipping {0}: {1} sources would write the same output {2}: {3}", new Object[]{f, claimants.size(), t, claimants});
            } else if (sources.contains(pathKey(t))) {
                LOGGER.log(Level.SEVERE, "Skipping {0}: output {1} would replace a source file", new Object[]{f, t});
            } else {
                engine.submit(new FileProcessor(params, f, t));
            }
        });
        engine.shutdown();
        int cc = -1;
        try {
            while (!engine.awaitTermination(1, TimeUnit.SECONDS)) {
                int curr = engine.getActiveCount()+engine.getQueue().size();
                if (cc!=curr) {
                    cc=curr;
                    if (params.verbose) LOGGER.log(Level.INFO, "Waiting for {0} job(s) to finish...", curr);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Engine shutdown");
    }

    /** Case-insensitive identity of a path, so collisions are also caught on case-insensitive file systems. */
    private static String pathKey(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static String getFileNameBase(File file) {
        String tail = file.getName();
        return tail.substring(0,tail.length()-4);
    }

    /**
     * Converts src into dest. Never writes over the source itself, and only replaces an existing
     * dest when replace is set; X2TIF swaps the output into place only after it is complete.
     */
    static void convert(HatchParameters params, File src, File dest, String series, boolean replace) {
        try {
            if (dest.exists()) {
                if (Files.isSameFile(src.toPath(), dest.toPath())) {
                    LOGGER.log(Level.SEVERE, "Refusing to replace source {0} with its own output", src);
                    return;
                }
                if (!replace) {
                    LOGGER.log(Level.SEVERE, "{0} already exists (use -o to overwrite)", dest);
                    return;
                }
            }
            try (X2TIF v2t = new X2TIF(params, src.toString(), dest.toString(), series == null ? null : Integer.valueOf(series))) {
                v2t.Execute();
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0} {1} {2}", new Object[]{src.toString(), dest.toString(), ex.toString()});
        }
    }

    public static void main(String[] args) {
        LOGGER.setLevel(Level.SEVERE);
        loci.common.DebugTools.setRootLevel("WARN");
        if (args.length==0) {
            System.out.println("please specify parameters.  Try 'hatch -help' for help! :-)");
            System.exit(0);
        }
        HatchParameters params = new HatchParameters();
        JCommander jc = JCommander.newBuilder().addObject(params).build();
        jc.setProgramName(Hatch.software+"\nhatch");
        try {
            jc.parse(args);
            LOGGER.log(Level.INFO,params.toString());
            if (params.verbose) {
                LOGGER.setLevel(Level.INFO);
            }
            if (params.isHelp()) {
                jc.usage();
                System.exit(0);
            } else if (params.src.exists()) {
                if (params.src.isDirectory()) {
                    if (!params.dest.exists()) {
                        params.dest.mkdir();
                    }
                    if (!params.dest.isDirectory()) {
                        jc.usage();
                    } else {
                        params.series.clear();  // ignore series parameter
                        Traverse(params);
                    }
                } else {
                    // Source is a single file
                    if (!params.dest.exists()&&(!params.dest.toString().toLowerCase().endsWith(".tif"))) {
                        params.dest.mkdirs();
                    }
                    if (params.dest.isDirectory()) {
                        if (params.series.isEmpty()) {
                            File dest = Path.of(params.dest.toString(),getFileNameBase(params.src)+".tif").toFile();
                            convert(params, params.src, dest, null, params.overwrite);
                        } else {
                            params.series.forEach(s->{
                                File dest = Path.of(params.dest.toString(),getFileNameBase(params.src)+"-series-"+s+".tif").toFile();
                                convert(params, params.src, dest, s, params.overwrite);
                            });
                        }
                    } else if (params.series.size()>1) {
                        jc.usage();
                    } else {
                        // Source and destination are a file
                        String series = params.series.isEmpty() ? null : params.series.get(0);
                        convert(params, params.src, params.dest, series, params.overwrite);
                    }
                }
            } else {
                LOGGER.log(Level.SEVERE, "{0} does not exist!", params.src.toString());
            }
        } catch (ParameterException ex) {
            LOGGER.severe(ex.getMessage());
        }
    }
}

class FileProcessor implements Callable<String> {
    private final HatchParameters params;
    private final File src;
    private final File dest;

    public FileProcessor(HatchParameters params, Path src, Path dest) {
        this.params = params;
        this.src = src.toFile();
        this.dest = dest.toFile();
    }

    @Override
    public String call() {
        boolean convert;
        if (!dest.exists() || params.overwrite) {
            convert = true;
        } else if (params.retry) {
            convert = !Validate.file(dest.toPath());
        } else {
            if (params.validate) {
                Validate.file(dest.toPath());
            }
            return null;
        }
        if (convert && !params.validateonly) {
            // an existing dest is only replaced once the new output is complete
            dest.getParentFile().mkdirs();
            Hatch.convert(params, src, dest, null, true);
        }
        if (dest.exists()&&((params.validate)||params.validateonly)) {
            Validate.file(dest.toPath());
        }
        return null;
    }
}
