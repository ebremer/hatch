package edu.stonybrook.bmi.hatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.PhotoInterp;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Validate} accepts complete pyramids and names what is wrong with broken ones. */
class ValidateTest {

    private static final int TILE = 256;

    private static Path converted(Path dir) throws Exception {
        Path src = dir.resolve("a.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.writeSlide(src.toFile(), 640, 480, TILE);
        HatchParameters p = new HatchParameters();
        p.src = src.toFile();
        p.dest = dest.toFile();
        try (X2TIF x = new X2TIF(p, src.toString(), dest.toString(), null)) {
            x.Execute();
        }
        return dest;
    }

    private static void assertInvalid(Path tiff, String expected) {
        FormatException ex = assertThrows(FormatException.class, () -> Validate.check(tiff));
        assertTrue(ex.getMessage().contains(expected), "expected \"" + expected + "\" in: " + ex.getMessage());
        assertFalse(Validate.file(tiff));
    }

    @Test
    void aConvertedPyramidIsValid(@TempDir Path dir) throws Exception {
        Path out = converted(dir);
        Validate.check(out);
        assertTrue(Validate.file(out));
    }

    @Test
    void aTruncatedFileIsInvalid(@TempDir Path dir) throws Exception {
        Path out = converted(dir);
        byte[] bytes = Files.readAllBytes(out);
        Path cut = dir.resolve("cut.tif");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length / 2));
        assertInvalid(cut, "truncated");
    }

    @Test
    void tagsThatContradictTheTilesAreInvalid(@TempDir Path dir) throws Exception {
        // ImageIO tiles are 4:2:0, but the IFD claims 4:4:4
        Path f = dir.resolve("wrong.tif");
        try (TiledTiffWriter w = new TiledTiffWriter(f.toString())) {
            IFD ifd = new IFD();
            ifd.put(IFD.IMAGE_WIDTH, (long) TILE);
            ifd.put(IFD.IMAGE_LENGTH, (long) TILE);
            ifd.put(IFD.TILE_WIDTH, TILE);
            ifd.put(IFD.TILE_LENGTH, TILE);
            ifd.put(IFD.COMPRESSION, 7);
            ifd.put(IFD.SAMPLES_PER_PIXEL, 3);
            ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 8, 8});
            ifd.put(IFD.PLANAR_CONFIGURATION, 1);
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            ifd.put(IFD.Y_CB_CR_SUB_SAMPLING, new int[] {1, 1});
            w.addImage(ifd).writeTile(0, 0, JPEGTools.blankTile(TILE, TILE, 0x808080, new JPEGTools.JpegInfo(3, 2, 2, false), 0.8f));
            w.finish();
        }
        assertInvalid(f, "tags say");
    }

    @Test
    void aMissingTileIsInvalid(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sparse.tif");
        TestFixtures.write(f.toFile(), TestFixtures.Image.jpeg(512, 512, TILE).skipping(3));
        assertInvalid(f, "not stored");
    }

    @Test
    void aSmallestLevelOver1024PixelsIsInvalid(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("big.tif");
        TestFixtures.writeSlide(f.toFile(), 1100, 300, TILE);
        assertInvalid(f, "must fit in 1024x1024");
    }

    @Test
    void levelsThatDoNotHalveAreInvalid(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("levels.tif");
        TestFixtures.write(f.toFile(), TestFixtures.Image.jpeg(512, 512, TILE), TestFixtures.Image.jpeg(200, 200, TILE));
        assertInvalid(f, "is not half of");
    }
}
