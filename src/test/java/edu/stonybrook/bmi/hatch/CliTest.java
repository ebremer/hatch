package edu.stonybrook.bmi.hatch;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Argument validation and exit codes: bad arguments fail before any file is touched, failures exit 1. */
class CliTest {

    private static Path slide(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        TestFixtures.writeSlide(p.toFile(), 512, 512, 256);
        return p;
    }

    private static void assertUsageError(Path dir, String... extra) throws Exception {
        Path in = dir.resolve("in");
        slide(in, "a.tif");
        Path out = dir.resolve("out");
        String[] args = new String[4 + extra.length];
        args[0] = "-src";
        args[1] = in.toString();
        args[2] = "-dest";
        args[3] = out.toString();
        System.arraycopy(extra, 0, args, 4, extra.length);

        assertEquals(1, Hatch.run(args), String.join(" ", extra) + " must be rejected");
        assertFalse(Files.exists(out), "rejected before the destination is created");
    }

    @Test
    void zeroFileProcessorsIsRejected(@TempDir Path dir) throws Exception {
        assertUsageError(dir, "-fp", "0");
    }

    @Test
    void nonNumericFileProcessorsIsRejected(@TempDir Path dir) throws Exception {
        assertUsageError(dir, "-fp", "two");
    }

    @Test
    void qualityAboveOneIsRejected(@TempDir Path dir) throws Exception {
        assertUsageError(dir, "-q", "1.5");
    }

    @Test
    void qualityOfZeroIsRejected(@TempDir Path dir) throws Exception {
        assertUsageError(dir, "-q", "0");
    }

    @Test
    void nonNumericSeriesIsRejected(@TempDir Path dir) throws Exception {
        assertUsageError(dir, "-s", "first");
    }

    @Test
    void removedJp2FlagIsRejected(@TempDir Path dir) throws Exception {
        assertUsageError(dir, "-jp2");
    }

    @Test
    void missingSourceExitsNonZero(@TempDir Path dir) {
        assertEquals(1, Hatch.run(new String[] {"-src", dir.resolve("nope.svs").toString(), "-dest", dir.resolve("out").toString()}));
    }

    @Test
    void logOptionAppendsErrorsToTheGivenFile(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("errors.log");
        assertEquals(1, Hatch.run(new String[] {"-src", dir.resolve("nope.svs").toString(),
            "-dest", dir.resolve("out").toString(), "-log", log.toString()}));
        assertTrue(Files.readString(log).contains("nope.svs"), "the error is in the log file");
    }

    @Test
    void successfulConversionExitsZero(@TempDir Path dir) throws Exception {
        Path src = slide(dir, "a.tif");
        Path dest = dir.resolve("a-out.tif");

        assertEquals(0, Hatch.run(new String[] {"-src", src.toString(), "-dest", dest.toString(), "-q", "0.9"}));
        assertTrue(Files.exists(dest));
    }

    @Test
    void batchWithOneBadFileConvertsTheRestAndExitsNonZero(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in");
        slide(in, "good.tif");
        Files.write(in.resolve("bad.tif"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Path out = dir.resolve("out");

        assertEquals(1, Hatch.run(new String[] {"-src", in.toString(), "-dest", out.toString(), "-fp", "2"}),
            "a failed file must surface in the exit code");
        assertTrue(Files.exists(out.resolve("good.tif")), "the good file is still converted");
        assertFalse(Files.exists(out.resolve("bad.tif")));
    }
}
