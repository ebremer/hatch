package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import loci.common.RandomAccessInputStream;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end conversion tests: synthesize a small tiled-JPEG "slide", run the full
 * {@link X2TIF} conversion, and assert the structure and bytes of the output pyramid.
 *
 * <p>The output is read back with the project's own {@link TiffParser} for structure
 * (level count, per-level dimensions, compression) and for raw tile bytes; an extracted
 * base tile is additionally decoded with {@link ImageIO} to confirm it is valid imagery.
 */
class HatchConversionTest {

    private static final int TILE = 256;

    @Test
    void convertsExactMultipleSlide(@TempDir Path dir) throws Exception {
        convertAndAssertPyramid(dir, 512, 512);
    }

    @Test
    void convertsSlideWithRemainderTiles(@TempDir Path dir) throws Exception {
        convertAndAssertPyramid(dir, 640, 480);
    }

    @Test
    void convertsSingleTileSlide(@TempDir Path dir) throws Exception {
        convertAndAssertPyramid(dir, 256, 200);
    }

    private void convertAndAssertPyramid(Path dir, int width, int height) throws Exception {
        File src = dir.resolve("slide_" + width + "x" + height + ".tif").toFile();
        File dest = dir.resolve("out_" + width + "x" + height + ".tif").toFile();
        TestFixtures.writeSlide(src, width, height, TILE);
        assertTrue(src.length() > 0, "fixture slide was written");

        HatchParameters p = new HatchParameters();
        p.src = src;
        p.dest = dest;
        p.quality = 0.8f;
        try (X2TIF x = new X2TIF(p, src.toString(), dest.toString(), null)) {
            x.Execute();
        }
        assertTrue(dest.exists() && dest.length() > 0, "output pyramid was written");

        int expectedDepth = TestFixtures.expectedDepth(width, height, TILE);

        // ---- structure of the output pyramid, via the project's own parser ----
        byte[] outBaseTile;
        try (RandomAccessInputStream in = new RandomAccessInputStream(dest.toString())) {
            TiffParser tp = new TiffParser(in);
            IFDList ifds = tp.getMainIFDs();

            assertEquals(expectedDepth, ifds.size(),
                "output pyramid level count must equal the computed depth");

            assertEquals(width, (int) ifds.get(0).getImageWidth(), "level 0 width == source");
            assertEquals(height, (int) ifds.get(0).getImageLength(), "level 0 height == source");

            for (int s = 0; s < ifds.size(); s++) {
                assertEquals(7, ((Number) ifds.get(s).getIFDValue(IFD.COMPRESSION)).intValue(),
                    "level " + s + " is JPEG-compressed (tag 7)");
                if (s > 0) {
                    long prevW = ifds.get(s - 1).getImageWidth();
                    long prevH = ifds.get(s - 1).getImageLength();
                    assertEquals(Math.round(prevW * 0.5), ifds.get(s).getImageWidth(),
                        "level " + s + " width is half the previous level");
                    assertEquals(Math.round(prevH * 0.5), ifds.get(s).getImageLength(),
                        "level " + s + " height is half the previous level");
                }
            }

            int last = ifds.size() - 1;
            assertTrue(ifds.get(last).getImageWidth() <= 1024
                    && ifds.get(last).getImageLength() <= 1024,
                "smallest level fits the <=1024 validation invariant");

            outBaseTile = tp.getRawTile(ifds.get(0), null, 0, 0);
        }

        // ---- byte-level: the base tile is a valid JPEG ----
        assertNotNull(outBaseTile, "base tile read back");
        assertEquals((byte) 0xFF, outBaseTile[0], "base tile starts with JPEG SOI");
        assertEquals((byte) 0xD8, outBaseTile[1]);
        assertEquals((byte) 0xFF, outBaseTile[outBaseTile.length - 2], "base tile ends with JPEG EOI");
        assertEquals((byte) 0xD9, outBaseTile[outBaseTile.length - 1]);

        // ---- lossless passthrough: base-level tile is copied verbatim (no re-encode) ----
        byte[] srcBaseTile;
        try (RandomAccessInputStream sin = new RandomAccessInputStream(src.toString())) {
            TiffParser stp = new TiffParser(sin);
            srcBaseTile = stp.getRawTile(stp.getMainIFDs().get(0), null, 0, 0);
        }
        assertArrayEquals(srcBaseTile, outBaseTile,
            "base-level JPEG tile must be transferred verbatim");

        // ---- independent decode: the extracted tile is real, full-size imagery ----
        BufferedImage tileImage = ImageIO.read(new ByteArrayInputStream(outBaseTile));
        assertNotNull(tileImage, "base tile decodes as a JPEG image");
        assertEquals(TILE, tileImage.getWidth(), "decoded tile width");
        assertEquals(TILE, tileImage.getHeight(), "decoded tile height");
    }
}
