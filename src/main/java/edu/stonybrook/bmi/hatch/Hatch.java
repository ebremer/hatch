package edu.stonybrook.bmi.hatch;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
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
    private static final Logger LOGGER = Logger.getLogger(Hatch.class.getName());

    public Hatch() {}

    /** Converts a folder tree; returns the number of files that failed or were refused. */
    private static int Traverse(HatchParameters params) {
        Path s;
        Path d;
        try {
            s = params.src.toPath().toRealPath();
            d = params.dest.toPath().toRealPath();
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0} {1} {2}", new Object[]{params.src.toString(), params.dest.toString(), ex.toString()});
            return 1;
        }
        if (d.startsWith(s)) {
            // outputs would land among the sources, where a .tif output can replace a .tif source
            LOGGER.log(Level.SEVERE, "Destination folder {0} must not be the source folder or inside it", d);
            return 1;
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
            return 1;
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
        int failures = 0;
        List<Future<Boolean>> results = new ArrayList<>();
        ThreadPoolExecutor engine = new ThreadPoolExecutor(params.fp,params.fp,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
        engine.prestartAllCoreThreads();
        for (Map.Entry<Path, Path> job : jobs.entrySet()) {
            Path f = job.getKey();
            Path t = job.getValue();
            List<Path> claimants = claims.get(pathKey(t));
            if (claimants.size() > 1) {
                LOGGER.log(Level.SEVERE, "Skipping {0}: {1} sources would write the same output {2}: {3}", new Object[]{f, claimants.size(), t, claimants});
                failures++;
            } else if (sources.contains(pathKey(t))) {
                LOGGER.log(Level.SEVERE, "Skipping {0}: output {1} would replace a source file", new Object[]{f, t});
                failures++;
            } else {
                results.add(engine.submit(new FileProcessor(params, f, t)));
            }
        }
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
            for (Future<Boolean> result : results) {
                if (!result.get()) {
                    failures++;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failures++;
        } catch (ExecutionException ex) {
            // FileProcessor catches everything itself; this is only a safety net
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0}", String.valueOf(ex.getCause()));
            failures++;
        }
        LOGGER.info("Engine shutdown");
        if (failures > 0) {
            LOGGER.log(Level.SEVERE, "{0} of {1} file(s) failed or were skipped", new Object[]{failures, jobs.size()});
        }
        return failures;
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
     *
     * @return true if dest now holds the converted image
     */
    static boolean convert(HatchParameters params, File src, File dest, String series, boolean replace) {
        try {
            if (dest.exists()) {
                if (Files.isSameFile(src.toPath(), dest.toPath())) {
                    LOGGER.log(Level.SEVERE, "Refusing to replace source {0} with its own output", src);
                    return false;
                }
                if (!replace) {
                    LOGGER.log(Level.SEVERE, "{0} already exists (use -o to overwrite)", dest);
                    return false;
                }
            }
            try (X2TIF v2t = new X2TIF(params, src.toString(), dest.toString(), series == null ? null : Integer.valueOf(series))) {
                v2t.Execute();
            }
            return true;
        } catch (Throwable ex) {
            // includes OutOfMemoryError: report this file and let the rest of a batch carry on
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0} {1} {2}", new Object[]{src.toString(), dest.toString(), ex.toString()});
            return false;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Reads the logging configuration; done once per run, since it resets every logger and handler. */
    private static void configureLogging() {
        try (InputStream config = Hatch.class.getResourceAsStream("/logging.properties")) {
            if (config != null) {
                LogManager.getLogManager().readConfiguration(config);
            }
        } catch (IOException | SecurityException ex) {
            LOGGER.log(Level.WARNING, "Failed to read logging.properties", ex);
        }
    }

    /** Runs hatch with the given arguments; returns the process exit code (0 = all files converted). */
    static int run(String[] args) {
        configureLogging();
        LOGGER.setLevel(Level.SEVERE);
        loci.common.DebugTools.setRootLevel("WARN");
        if (args.length==0) {
            System.out.println("please specify parameters.  Try 'hatch -help' for help! :-)");
            return 1;
        }
        HatchParameters params = new HatchParameters();
        JCommander jc = JCommander.newBuilder().addObject(params).build();
        jc.setProgramName(Hatch.software+"\nhatch");
        try {
            jc.parse(args);
            if (params.isHelp()) {
                jc.usage();
                return 0;
            }
            params.validate();
        } catch (ParameterException ex) {
            LOGGER.severe(ex.getMessage());
            return 1;
        }
        if (params.verbose) {
            LOGGER.setLevel(Level.INFO);
        }
        FileHandler errorLog = null;
        if (params.log != null) {
            try {
                errorLog = new FileHandler(params.log.getPath(), true);
                errorLog.setLevel(Level.SEVERE);
                errorLog.setFormatter(new SingleLineFormatter());
                Logger.getLogger("").addHandler(errorLog);
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "Cannot write the log file {0}: {1}", new Object[]{params.log, ex.toString()});
                return 1;
            }
        }
        try {
            return convertAll(params, jc);
        } finally {
            if (errorLog != null) {
                Logger.getLogger("").removeHandler(errorLog);
                errorLog.close();
            }
        }
    }

    private static int convertAll(HatchParameters params, JCommander jc) {
        if (!params.src.exists()) {
            LOGGER.log(Level.SEVERE, "{0} does not exist!", params.src.toString());
            return 1;
        }
        if (params.src.isDirectory()) {
            if (!params.dest.exists()) {
                params.dest.mkdir();
            }
            if (!params.dest.isDirectory()) {
                jc.usage();
                return 1;
            }
            params.series.clear();  // ignore series parameter
            return Traverse(params) == 0 ? 0 : 1;
        }
        // Source is a single file
        if (!params.dest.exists()&&(!params.dest.toString().toLowerCase().endsWith(".tif"))) {
            params.dest.mkdirs();
        }
        boolean ok = true;
        if (params.dest.isDirectory()) {
            if (params.series.isEmpty()) {
                File dest = Path.of(params.dest.toString(),getFileNameBase(params.src)+".tif").toFile();
                ok = convert(params, params.src, dest, null, params.overwrite);
            } else {
                for (String s : params.series) {
                    File dest = Path.of(params.dest.toString(),getFileNameBase(params.src)+"-series-"+s+".tif").toFile();
                    ok &= convert(params, params.src, dest, s, params.overwrite);
                }
            }
        } else if (params.series.size()>1) {
            jc.usage();
            ok = false;
        } else {
            // Source and destination are a file
            String series = params.series.isEmpty() ? null : params.series.get(0);
            ok = convert(params, params.src, params.dest, series, params.overwrite);
        }
        return ok ? 0 : 1;
    }
}

class FileProcessor implements Callable<Boolean> {
    private static final Logger LOGGER = Logger.getLogger(FileProcessor.class.getName());
    private final HatchParameters params;
    private final File src;
    private final File dest;

    public FileProcessor(HatchParameters params, Path src, Path dest) {
        this.params = params;
        this.src = src.toFile();
        this.dest = dest.toFile();
    }

    /** @return false if the conversion or a requested validation failed */
    @Override
    public Boolean call() {
        try {
            boolean convert;
            if (!dest.exists() || params.overwrite) {
                convert = true;
            } else if (params.retry) {
                convert = !Validate.file(dest.toPath());
            } else {
                return !params.validate || Validate.file(dest.toPath());
            }
            boolean ok = true;
            if (convert && !params.validateonly) {
                // an existing dest is only replaced once the new output is complete
                dest.getParentFile().mkdirs();
                ok = Hatch.convert(params, src, dest, null, true);
            }
            if (dest.exists()&&((params.validate)||params.validateonly)) {
                ok &= Validate.file(dest.toPath());
            }
            return ok;
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, "FILE PROCESSOR ERROR --> {0} {1} {2}", new Object[]{src, dest, ex.toString()});
            return false;
        }
    }
}
