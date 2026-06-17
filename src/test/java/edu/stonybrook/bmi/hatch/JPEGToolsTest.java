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
