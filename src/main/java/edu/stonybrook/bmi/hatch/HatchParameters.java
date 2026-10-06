package edu.stonybrook.bmi.hatch;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.IntegerConverter;
import com.beust.jcommander.internal.Lists;
import java.io.File;
import java.util.List;

/**
 *
 * @author erich
 */
public class HatchParameters {
    @Parameter(names = {"-help","-h"}, help = true)
    private boolean help;
    
    public boolean isHelp() {
        return help;
    }
    
    @Parameter(names = "-src", description = "Source file (.vsi, .svs, .tif, .tiff) or folder", required = true)
    public File src;

    @Parameter(names = "-dest", description = "Destination .tif/.tiff file, or folder", required = true)
    public File dest;  
    
    @Parameter(names = "-fp", description = "Number of files converted at once (batch mode)", converter = IntegerConverter.class, validateWith = PositiveInteger.class)
    public Integer fp = 1;

    @Parameter(names = {"-filter", "-f"}, description = "Batch mode: only convert files whose path contains this text")
    public String filter = null;
    
    @Parameter(names = {"-v","-verbose"}, description = "Log progress")
    public boolean verbose = false;

    @Parameter(names = {"-o","-overwrite"}, description = "Replace existing outputs")
    public boolean overwrite = false;
    
    @Parameter(names = {"-r","-retry"}, description = "Batch mode: replace existing outputs that fail validation, keep the rest")
    public boolean retry = false;
    
    @Parameter(names = {"-validate"}, description = "Batch mode: validate every output, new or existing")
    public boolean validate = false;

    @Parameter(names = {"-validateonly"}, description = "Batch mode: validate existing outputs, convert nothing")
    public boolean validateonly = false;
    
    @Parameter(names = {"-quality","-q"}, description = "JPEG quality of the reduced pyramid levels, 0.0 < q <= 1.0 "
        + "(the full-resolution level is copied, not re-encoded). 1.0 is several times larger for little visible gain")
    public float quality = 0.9f;
    
    @Parameter(names = "-log", description = "Append error messages to this file")
    public File log = null;

    @Parameter(names = {"-s","-series"}, description = "Single-file mode: convert these series (comma-separated) instead of the largest image")
    public List<String> series = Lists.newArrayList();    

    /** Checks the values JCommander cannot, before any file is touched. */
    public void validate() {
        if (!(quality > 0f && quality <= 1f)) {
            throw new ParameterException("-quality must be greater than 0 and at most 1 (found " + quality + ")");
        }
        for (String s : series) {
            if (!s.matches("\\d+")) {
                throw new ParameterException("-series values must be non-negative integers (found \"" + s + "\")");
            }
        }
    }
}
