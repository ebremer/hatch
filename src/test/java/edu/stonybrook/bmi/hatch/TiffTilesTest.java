package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.PhotoInterp;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The TIFF tile path end to end: {@link TiledTiffWriter} chaining and sparse tiles,
 * {@link JpegTiffTiles} table splicing, and readers that release their file when they fail.
 */
class TiffTilesTest {

    private static final int TILE = 64;

    private static BufferedImage picture() {
        BufferedImage bi = new BufferedImage(TILE, TILE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color(150, 60, 90));
        g.fillRect(0, 0, TILE, TILE);
        g.setColor(new Color(20, 200, 120));
        g.fillOval(8, 8, 40, 30);
        g.dispose();
        return bi;
    }

    /** Splits a JFIF JPEG into an Aperio-style tables stream and an abbreviated tile (no JFIF marker). */
    private static byte[][] abbreviate(byte[] jpeg) {
        ByteArrayOutputStream tables = new ByteArrayOutputStream();
        ByteArrayOutputStream tile = new ByteArrayOutputStream();
        tables.write(0xFF);
        tables.write(0xD8);
        tile.write(0xFF);
        tile.write(0xD8);
        int pos = 2;
        while (true) {
            int marker = jpeg[pos + 1] & 0xff;
            if (marker == 0xDA) {
                tile.write(jpeg, pos, jpeg.length - pos); // scan through EOI
                break;
            }
            int length = ((jpeg[pos + 2] & 0xff) << 8) | (jpeg[pos + 3] & 0xff);
            if (marker == 0xDB || marker == 0xC4) {
                tables.write(jpeg, pos, 2 + length);
            } else if (marker != 0xE0) {
                tile.write(jpeg, pos, 2 + length);
            }
            pos += 2 + length;
        }
        tables.write(0xFF);
        tables.write(0xD9);
        return new byte[][] {tables.toByteArray(), tile.toByteArray()};
    }

    private static IFD tiledIFD(int width, int height) {
        IFD ifd = new IFD();
        ifd.put(IFD.IMAGE_WIDTH, (long) width);
        ifd.put(IFD.IMAGE_LENGTH, (long) height);
        ifd.put(IFD.TILE_WIDTH, TILE);
        ifd.put(IFD.TILE_LENGTH, TILE);
        ifd.put(IFD.COMPRESSION, 7);
        ifd.put(IFD.SAMPLES_PER_PIXEL, 3);
        ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 8, 8});
        ifd.put(IFD.PLANAR_CONFIGURATION, 1);
        return ifd;
    }

    private static short[] unsigned(byte[] b) {
        short[] s = new short[b.length];
        for (int i = 0; i < b.length; i++) {
            s[i] = (short) (b[i] & 0xff);
        }
        return s;
    }

    private static File abbreviatedTiff(Path dir, PhotoInterp photometric, byte[][] parts) throws Exception {
        File f = dir.resolve("tables-" + photometric + ".tif").toFile();
        try (TiledTiffWriter w = new TiledTiffWriter(f.toString())) {
            IFD ifd = tiledIFD(TILE, TILE);
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, photometric.getCode());
            ifd.put(IFD.JPEG_TABLES, unsigned(parts[0])); // TiffSaver writes short[] as BYTE
            w.addImage(ifd).writeTile(0, 0, parts[1]);
            w.finish();
        }
        return f;
    }

    @Test
    void sharedJpegTablesAreSplicedIntoAStandaloneYCbCrJpeg(@TempDir Path dir) throws Exception {
        byte[] full = JPEGTools.encode(picture(), 0.9f);
        File f = abbreviatedTiff(dir, PhotoInterp.Y_CB_CR, abbreviate(full));

        byte[] spliced = TestFixtures.rawTile(f, 0, 0, 0);

        assertEquals(new JPEGTools.JpegInfo(3, 2, 2, false), JPEGTools.inspect(spliced), "APP14 says YCbCr");
        assertEquals(spliced.length, JPEGTools.streamLength(spliced), "one complete JPEG stream");
        assertArrayEquals(PyramidBuilder.bgr(JPEGTools.decode(full)), PyramidBuilder.bgr(JPEGTools.decode(spliced)),
            "decodes to exactly the pixels of the original JPEG");
    }

    @Test
    void sharedJpegTablesUnderRgbPhotometricGetAnRgbMarker(@TempDir Path dir) throws Exception {
        File f = abbreviatedTiff(dir, PhotoInterp.RGB, abbreviate(JPEGTools.encode(picture(), 0.9f)));

        assertTrue(JPEGTools.inspect(TestFixtures.rawTile(f, 0, 0, 0)).rgb(),
            "the Photometric tag decides the colour transform, recorded as Adobe transform 0");
    }

    @Test
    void writerChainsImagesInOrderAndRecordsUnwrittenTilesAsSparse(@TempDir Path dir) throws Exception {
        File f = dir.resolve("two.tif").toFile();
        byte[] a = JPEGTools.encode(picture(), 0.5f);
        byte[] b = JPEGTools.encode(picture(), 0.9f);
        try (TiledTiffWriter w = new TiledTiffWriter(f.toString())) {
            IFD first = tiledIFD(3 * TILE, TILE);
            first.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            IFD second = tiledIFD(TILE, TILE);
            second.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            TiledTiffWriter.Image one = w.addImage(first);
            TiledTiffWriter.Image two = w.addImage(second);
            two.writeTile(0, 0, b);   // images and tiles in any order
            one.writeTile(2, 0, b);
            one.writeTile(0, 0, a);
            w.finish();
        }

        assertEquals(2, TestFixtures.ifdChain(f).size(), "chain ends after the second image");
        IFDList ifds = TestFixtures.ifds(f);
        assertEquals(3 * TILE, ifds.get(0).getImageWidth(), "images in the order they were added");
        assertArrayEquals(a, TestFixtures.rawTile(f, 0, 0, 0));
        assertNull(TestFixtures.rawTile(f, 0, 0, 1), "never written: offset and byte count 0");
        assertArrayEquals(b, TestFixtures.rawTile(f, 0, 0, 2));
        assertArrayEquals(b, TestFixtures.rawTile(f, 1, 0, 0));
    }

    @Test
    void tileOutsideTheGridIsRejected(@TempDir Path dir) throws Exception {
        File f = dir.resolve("a.tif").toFile();
        TestFixtures.writeSlide(f, 2 * TILE, TILE, TILE);
        assertThrows(FormatException.class, () -> TestFixtures.rawTile(f, 0, 1, 0));
        assertThrows(FormatException.class, () -> TestFixtures.rawTile(f, 0, 0, -1));
    }

    @Test
    void truncatedSourceTileIsAnErrorNotAMissingTile(@TempDir Path dir) throws Exception {
        File f = dir.resolve("a.tif").toFile();
        TestFixtures.writeSlide(f, 2 * TILE, TILE, TILE);
        IFD ifd = TestFixtures.ifds(f).get(0);
        long endOfTile1 = ifd.getIFDLongArray(IFD.TILE_OFFSETS)[1] + ifd.getIFDLongArray(IFD.TILE_BYTE_COUNTS)[1];
        // the same file cut off inside tile 1, read with the intact file's IFD
        Path cut = dir.resolve("cut.tif");
        Files.write(cut, Arrays.copyOf(Files.readAllBytes(f.toPath()), (int) endOfTile1 - 10));
        try (RandomAccessInputStream in = new RandomAccessInputStream(f.toString());
             TileFile file = new TileFile(cut.toString())) {
            JpegTiffTiles tiles = new JpegTiffTiles(file, in, ifd);
            assertTrue(tiles.read(0, 0).length > 0, "tile 0 is intact");
            FormatException ex = assertThrows(FormatException.class, () -> tiles.read(0, 1));
            assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
        }
    }

    @Test
    void tiffReaderReleasesTheFileWhenItCannotOpenIt(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.tif");
        Files.write(bad, new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 2, 3});
        HatchTiffReader reader = new HatchTiffReader();
        assertThrows(Exception.class, () -> reader.setId(bad.toString()));
        Files.delete(bad); // fails on Windows while a stream is still open
    }

    @Test
    void vsiReaderReleasesTheFileWhenItCannotOpenIt(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.vsi");
        Files.write(bad, new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 2, 3});
        CellSensReader reader = new CellSensReader();
        assertThrows(Exception.class, () -> reader.setId(bad.toString()));
        Files.delete(bad);
    }
}
