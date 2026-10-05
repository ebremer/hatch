package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffRational;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conversion behaviour that depends on what the source actually contains: the output's tags
 * must describe the copied JPEG streams, absent tiles must be filled, inputs that cannot be
 * copied verbatim must be refused up front, and calibration must be carried over correctly.
 */
class InputHandlingTest {

    private static final int TILE = 256;

    private static HatchParameters params(Path src, Path dest) {
        HatchParameters p = new HatchParameters();
        p.src = src.toFile();
        p.dest = dest.toFile();
        p.quality = 0.8f;
        return p;
    }

    private static void convert(Path src, Path dest) throws Exception {
        try (X2TIF x = new X2TIF(params(src, dest), src.toString(), dest.toString(), null)) {
            x.Execute();
        }
    }

    private static IFDList ifds(Path tiff) throws Exception {
        return TestFixtures.ifds(tiff.toFile());
    }

    private static byte[] tile(Path tiff, int image, int row, int col) throws Exception {
        return TestFixtures.rawTile(tiff.toFile(), image, row, col);
    }

    private static String xmp(IFD ifd) {
        Object v = ifd.getIFDValue(700);
        byte[] b;
        if (v instanceof byte[] bytes) {
            b = bytes;
        } else {
            short[] s = (short[]) v;
            b = new byte[s.length];
            for (int i = 0; i < s.length; i++) {
                b[i] = (byte) s[i];
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** The numbers inside the XMP PixelSpacing property, or an empty list if it is absent. */
    private static List<Double> pixelSpacing(String xmp) {
        List<Double> values = new ArrayList<>();
        int start = xmp.indexOf("<DICOM:PixelSpacing");
        if (start < 0) {
            return values;
        }
        String block = xmp.substring(start, xmp.indexOf("</DICOM:PixelSpacing>", start));
        Matcher m = Pattern.compile(">\\s*([0-9.Ee-]+)\\s*<").matcher(block);
        while (m.find()) {
            values.add(Double.valueOf(m.group(1)));
        }
        return values;
    }

    @Test
    void baseLevelTagsDescribeTheCopiedJpegStreams(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("a.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.writeSlide(src.toFile(), 512, 512, TILE);

        convert(src, dest);

        IFD base = ifds(dest).get(0);
        JPEGTools.JpegInfo stream = JPEGTools.inspect(tile(dest, 0, 0, 0));
        assertEquals(new JPEGTools.JpegInfo(3, 2, 2, false), stream, "fixture tiles are ImageIO JFIF 4:2:0");
        assertEquals(6, base.getIFDIntValue(IFD.PHOTOMETRIC_INTERPRETATION), "YCbCr, as the JFIF stream says");
        assertArrayEquals(new int[] {2, 2}, base.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING),
            "subsampling must match the JPEG frame header, not a hard-coded [1,1]");
    }

    @Test
    void sparseTilesAreFilledWithBackgroundTilesEncodedLikeTheRest(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("sparse.tif");
        Path dest = dir.resolve("out.tif");
        // 3x2 tiles; tile 4 (row 1, col 1) is never written
        TestFixtures.write(src.toFile(), TestFixtures.Image.jpeg(768, 512, TILE).skipping(4));
        assertNull(tile(src, 0, 1, 1), "source really has no tile there");

        convert(src, dest);

        assertEquals(TestFixtures.expectedDepth(768, 512, TILE), TestFixtures.ifdChain(dest.toFile()).size());
        byte[] filled = tile(dest, 0, 1, 1);
        assertNotNull(filled, "the gap is filled in the output");
        assertEquals(JPEGTools.inspect(tile(dest, 0, 0, 0)), JPEGTools.inspect(filled),
            "the filler is encoded exactly like the copied tiles");
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(filled));
        int rgb = bi.getRGB(TILE / 2, TILE / 2) & 0xFFFFFF;
        assertTrue(((rgb >> 16) & 0xff) > 245 && ((rgb >> 8) & 0xff) > 245 && (rgb & 0xff) > 245,
            String.format("filled with white background, got %06X", rgb));
    }

    @Test
    void uncompressedTilesAreRefusedBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("raw.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.write(src.toFile(), TestFixtures.Image.jpeg(512, 512, TILE).uncompressed());

        FormatException ex = assertThrows(FormatException.class, () -> convert(src, dest));

        assertTrue(ex.getMessage().contains("only JPEG"), ex.getMessage());
        assertFalse(Files.exists(dest));
    }

    @Test
    void grayscaleImagesAreRefused(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("gray.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.write(src.toFile(), TestFixtures.Image.jpeg(512, 512, TILE).gray());

        FormatException ex = assertThrows(FormatException.class, () -> convert(src, dest));

        assertTrue(ex.getMessage().contains("8-bit RGB"), ex.getMessage());
        assertFalse(Files.exists(dest));
    }

    @Test
    void tileGridComesFromTheImageBeingConverted(@TempDir Path dir) throws Exception {
        // IFD 0 is a small image with 128-px tiles; the largest image (converted) is IFD 1 with 256-px tiles
        Path src = dir.resolve("two.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.write(src.toFile(),
            TestFixtures.Image.jpeg(200, 150, 128),
            TestFixtures.Image.jpeg(640, 480, TILE));

        convert(src, dest);

        IFD base = ifds(dest).get(0);
        assertEquals(640, base.getImageWidth());
        assertEquals(480, base.getImageLength());
        assertEquals(TILE, base.getTileWidth(), "tile size of the converted image, not of IFD 0");
        assertArrayEquals(tile(src, 1, 1, 2), tile(dest, 0, 1, 2), "tiles copied verbatim from IFD 1");
    }

    @Test
    void uncalibratedSourcesGetNoResolutionOrSpacing(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("a.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.writeSlide(src.toFile(), 512, 512, TILE);

        convert(src, dest);

        IFD base = ifds(dest).get(0);
        assertFalse(base.containsKey(IFD.X_RESOLUTION), "no 0/1000 resolution for an uncalibrated image");
        assertFalse(base.containsKey(IFD.Y_RESOLUTION));
        assertTrue(pixelSpacing(xmp(base)).isEmpty(), "no PixelSpacing without calibration");
    }

    @Test
    void pixelSpacingIsRecordedInMillimetres(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("cal.tif");
        Path dest = dir.resolve("out.tif");
        TestFixtures.write(src.toFile(), TestFixtures.Image.jpeg(512, 512, TILE).calibrated(0.25));

        convert(src, dest);

        IFD base = ifds(dest).get(0);
        List<Double> spacing = pixelSpacing(xmp(base));
        assertEquals(2, spacing.size(), "row and column spacing");
        assertEquals(0.00025, spacing.get(0), 1e-9, "0.25 um/pixel is 0.00025 mm/pixel");
        assertEquals(0.00025, spacing.get(1), 1e-9);
        TiffRational xres = (TiffRational) base.getIFDValue(IFD.X_RESOLUTION);
        assertEquals(40000, xres.doubleValue(), 1, "0.25 um/pixel is 40000 pixels/cm");
        assertEquals(3, base.getIFDIntValue(IFD.RESOLUTION_UNIT), "centimetres");
    }
}
