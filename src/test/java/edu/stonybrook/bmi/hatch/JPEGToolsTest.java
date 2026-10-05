package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link JPEGTools#FindFirstEOI}, including the bounds/null hardening. */
class JPEGToolsTest {

    private static byte[] sampleJpeg() throws IOException {
        BufferedImage bi = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color(40, 90, 160));
        g.fillRect(0, 0, 48, 32);
        g.setColor(Color.WHITE);
        g.drawLine(0, 0, 48, 32);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpeg", baos);
        return baos.toByteArray();
    }

    private static RandomAccessInputStream rais(Path dir, String name, byte[] data) throws IOException {
        Path f = dir.resolve(name);
        Files.write(f, data);
        return new RandomAccessInputStream(f.toString());
    }

    @Test
    void extractsJpegUpToFirstEOIAndIgnoresTrailer(@TempDir Path dir) throws IOException {
        byte[] jpeg = sampleJpeg();
        byte[] withTrailer = new byte[jpeg.length + 8];
        System.arraycopy(jpeg, 0, withTrailer, 0, jpeg.length);
        for (int i = jpeg.length; i < withTrailer.length; i++) {
            withTrailer[i] = 0x42; // junk after the real EOI
        }

        byte[] buf = new byte[withTrailer.length + 16];
        byte[] result;
        try (RandomAccessInputStream in = rais(dir, "trailer.bin", withTrailer)) {
            result = JPEGTools.FindFirstEOI(in, buf);
        }

        assertEquals(jpeg.length, result.length, "stops at first EOI, excludes trailer");
        assertEquals((byte) 0xFF, result[0]);
        assertEquals((byte) 0xD8, result[1]);
        assertEquals((byte) 0xFF, result[result.length - 2]);
        assertEquals((byte) 0xD9, result[result.length - 1]);
    }

    @Test
    void throwsWhenBufferTooSmall(@TempDir Path dir) throws IOException {
        // Regression: previously overran the buffer with ArrayIndexOutOfBoundsException.
        byte[] jpeg = sampleJpeg();
        byte[] tiny = new byte[jpeg.length / 2];
        try (RandomAccessInputStream in = rais(dir, "small.bin", jpeg)) {
            assertThrows(IOException.class, () -> JPEGTools.FindFirstEOI(in, tiny),
                "buffer overflow must surface as IOException, not AIOOBE");
        }
    }

    @Test
    void inspectReadsSamplingAndColourSpaceFromTheStream() throws Exception {
        assertEquals(new JPEGTools.JpegInfo(3, 2, 2, false), JPEGTools.inspect(sampleJpeg()),
            "ImageIO default: JFIF YCbCr 4:2:0");
    }

    @Test
    void inspectReportsGrayscaleComponentCount() throws Exception {
        BufferedImage gray = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(gray, "jpeg", baos);
        assertEquals(1, JPEGTools.inspect(baos.toByteArray()).components());
    }

    @Test
    void inspectRejectsProgressiveAndNonJpegData() throws Exception {
        BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
        javax.imageio.ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
        p.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (javax.imageio.stream.MemoryCacheImageOutputStream out = new javax.imageio.stream.MemoryCacheImageOutputStream(baos)) {
            w.setOutput(out);
            w.write(null, new javax.imageio.IIOImage(bi, null, null), p);
        } finally {
            w.dispose();
        }
        assertThrows(FormatException.class, () -> JPEGTools.inspect(baos.toByteArray()), "progressive JPEG");
        assertThrows(FormatException.class, () -> JPEGTools.inspect(new byte[] {1, 2, 3, 4, 5}), "not a JPEG");
    }

    @Test
    void blankTilesMatchTheRequestedStructureAndColour() throws Exception {
        JPEGTools.JpegInfo[] structures = {
            new JPEGTools.JpegInfo(3, 2, 2, false),
            new JPEGTools.JpegInfo(3, 2, 1, false),
            new JPEGTools.JpegInfo(3, 1, 1, false),
            new JPEGTools.JpegInfo(3, 1, 1, true),
        };
        for (JPEGTools.JpegInfo like : structures) {
            byte[] jpeg = JPEGTools.blankTile(256, 240, 0xF2E6D8, like, 0.9f);
            assertEquals(like, JPEGTools.inspect(jpeg), "structure of " + like);
            BufferedImage bi = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
            assertEquals(256, bi.getWidth());
            assertEquals(240, bi.getHeight());
            int rgb = bi.getRGB(100, 100);
            assertEquals(0xF2, (rgb >> 16) & 0xff, 2, "red of " + like);
            assertEquals(0xE6, (rgb >> 8) & 0xff, 2, "green of " + like);
            assertEquals(0xD8, rgb & 0xff, 2, "blue of " + like);
        }
    }

    @Test
    void throwsWhenNoEOIMarker(@TempDir Path dir) throws IOException {
        // Regression: previously returned null, which became corrupt tile data downstream.
        byte[] noMarker = new byte[64];
        for (int i = 0; i < noMarker.length; i++) {
            noMarker[i] = 0x11;
        }
        byte[] buf = new byte[256];
        try (RandomAccessInputStream in = rais(dir, "nomarker.bin", noMarker)) {
            assertThrows(IOException.class, () -> JPEGTools.FindFirstEOI(in, buf),
                "missing EOI must surface as IOException, not null");
        }
    }
}
