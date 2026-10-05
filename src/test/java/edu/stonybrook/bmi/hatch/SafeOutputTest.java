package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import loci.formats.FormatException;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for output safety: Hatch must never delete or replace a source, never replace
 * an existing output without {@code -o}, never let two sources race for one output, and never
 * leave a partial file behind (or destroy a good one) when a conversion fails.
 */
class SafeOutputTest {

    private static final int SIZE = 512;
    private static final int TILE = 256;
    private static final byte[] OLD_OUTPUT = "previous output".getBytes(StandardCharsets.US_ASCII);

    private static Path slide(Path dir, String name) throws IOException, FormatException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        TestFixtures.writeSlide(p.toFile(), SIZE, SIZE, TILE);
        return p;
    }

    private static boolean isCompletePyramid(Path p) throws IOException {
        return TestFixtures.ifdChain(p.toFile()).size() == TestFixtures.expectedDepth(SIZE, SIZE, TILE);
    }

    private static long count(Path dir, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(suffix)).count();
        }
    }

    private static HatchParameters params(Path src, Path dest) {
        HatchParameters p = new HatchParameters();
        p.src = src.toFile();
        p.dest = dest.toFile();
        p.quality = 0.8f;
        return p;
    }

    // ---- single-file mode ----

    @Test
    void singleFileNeverReplacesItsOwnSource(@TempDir Path dir) throws Exception {
        Path src = slide(dir, "a.tif");
        byte[] before = Files.readAllBytes(src);

        assertEquals(1, Hatch.run(new String[] {"-src", src.toString(), "-dest", src.toString(), "-o"}));

        assertArrayEquals(before, Files.readAllBytes(src), "-src X -dest X -o must leave X untouched");
    }

    @Test
    void singleFileKeepsExistingOutputUnlessOverwriting(@TempDir Path dir) throws Exception {
        Path src = slide(dir, "a.tif");
        Path dest = dir.resolve("out.tif");
        Files.write(dest, OLD_OUTPUT);

        assertEquals(1, Hatch.run(new String[] {"-src", src.toString(), "-dest", dest.toString()}),
            "refusing to replace an output is reported as a failure");
        assertArrayEquals(OLD_OUTPUT, Files.readAllBytes(dest), "existing output kept without -o");

        assertEquals(0, Hatch.run(new String[] {"-src", src.toString(), "-dest", dest.toString(), "-o"}));
        assertTrue(isCompletePyramid(dest), "-o replaces it with a complete pyramid");
    }

    // ---- batch (folder) mode ----

    @Test
    void batchRefusesDestinationEqualToSource(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in");
        Path a = slide(in, "a.tif");
        byte[] before = Files.readAllBytes(a);

        assertEquals(1, Hatch.run(new String[] {"-src", in.toString(), "-dest", in.toString(), "-o"}));

        assertArrayEquals(before, Files.readAllBytes(a), "-src D -dest D -o must leave sources untouched");
        assertEquals(1, count(in, ".tif"), "nothing written into the source folder");
    }

    @Test
    void batchRefusesDestinationInsideSource(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in");
        slide(in, "a.tif");
        Path out = in.resolve("out");

        assertEquals(1, Hatch.run(new String[] {"-src", in.toString(), "-dest", out.toString(), "-o"}));

        assertEquals(0, count(out, ".tif"), "nothing converted into a folder nested in the source");
    }

    @Test
    void batchSkipsSourcesCompetingForOneOutput(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in");
        Path a = slide(in, "a.tif");
        Files.copy(a, in.resolve("a.svs")); // a.tif and a.svs would both write out/a.tif
        slide(in, "b.tif");
        Path out = dir.resolve("out");

        assertEquals(1, Hatch.run(new String[] {"-src", in.toString(), "-dest", out.toString(), "-fp", "2"}),
            "skipped sources make the run fail");

        assertFalse(Files.exists(out.resolve("a.tif")), "colliding sources are refused, not raced");
        assertTrue(isCompletePyramid(out.resolve("b.tif")), "unrelated sources still convert");
    }

    @Test
    void batchNeverReplacesASourceWhenSourceIsInsideDestination(@TempDir Path dir) throws Exception {
        // src = root/in, dest = root: root/in/in/x.tif maps onto root/in/x.tif, which is a source
        Path root = dir.resolve("root");
        Path in = root.resolve("in");
        Path x = slide(in, "x.tif");
        slide(in.resolve("in"), "x.tif");
        byte[] before = Files.readAllBytes(x);

        assertEquals(1, Hatch.run(new String[] {"-src", in.toString(), "-dest", root.toString(), "-o"}));

        assertArrayEquals(before, Files.readAllBytes(x), "source root/in/x.tif must not be replaced");
        assertTrue(isCompletePyramid(root.resolve("x.tif")), "root/in/x.tif itself still converts");
    }

    // ---- failed conversions ----

    @Test
    void failedConversionKeepsExistingOutputAndLeavesNoPartialFile(@TempDir Path dir) throws Exception {
        Path src = slide(dir, "a.tif");
        Path dest = dir.resolve("out.tif");
        Files.write(dest, OLD_OUTPUT);
        HatchParameters p = params(src, dest);
        p.quality = 1.5f; // rejected by the JPEG encoder only after the base level has been written

        assertThrows(RuntimeException.class, () -> {
            try (X2TIF x = new X2TIF(p, src.toString(), dest.toString(), null)) {
                x.Execute();
            }
        });

        assertArrayEquals(OLD_OUTPUT, Files.readAllBytes(dest), "a failed run must not destroy the old output");
        assertEquals(0, count(dir, ".part"), "no partial file left behind");
    }

    @Test
    void failedConversionCreatesNoOutputAndExitsNonZero(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.tif");
        Files.write(bad, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Path dest = dir.resolve("out.tif");

        assertEquals(1, Hatch.run(new String[] {"-src", bad.toString(), "-dest", dest.toString()}));

        assertFalse(Files.exists(dest), "no output for a failed conversion, so a re-run won't skip it");
        assertEquals(0, count(dir, ".part"), "no partial file left behind");
    }

    @Test
    void unreadableSourceFailsInConstructorWithItsRealCause(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.tif");
        Files.write(bad, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Path dest = dir.resolve("out.tif");

        Exception ex = assertThrows(Exception.class,
            () -> new X2TIF(params(bad, dest), bad.toString(), dest.toString(), null));

        assertTrue(ex instanceof FormatException || ex instanceof IOException,
            "the read failure itself surfaces, not a later NPE/ArithmeticException: " + ex);
        assertFalse(Files.exists(dest), "no output created");
        // on Windows this fails if the reader's file handle leaked
        Files.delete(bad);
    }
}
