package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import loci.formats.FormatException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** Tests for the JPEG stream helpers in {@link JPEGTools}. */
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

    @Test
    void streamLengthStopsAtTheEndOfTheJpegAndIgnoresTrailingBytes() throws IOException {
        byte[] jpeg = sampleJpeg();
        byte[] padded = Arrays.copyOf(jpeg, jpeg.length + 9);
        Arrays.fill(padded, jpeg.length, padded.length, (byte) 0x42);

        assertEquals(jpeg.length, JPEGTools.streamLength(jpeg));
        assertEquals(jpeg.length, JPEGTools.streamLength(padded), "trailing padding is not part of the stream");
    }

    @Test
    void streamLengthIsNotFooledByAnEoiInsideAMarkerSegment() throws IOException {
        // an APP1 segment (as for an EXIF thumbnail) that contains FF D9 right after SOI
        byte[] jpeg = sampleJpeg();
        byte[] app1 = {(byte) 0xFF, (byte) 0xE1, 0, 8, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9, 0, 0};
        byte[] withThumbnail = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, withThumbnail, 0, 2);
        System.arraycopy(app1, 0, withThumbnail, 2, app1.length);
        System.arraycopy(jpeg, 2, withThumbnail, 2 + app1.length, jpeg.length - 2);

        assertEquals(withThumbnail.length, JPEGTools.streamLength(withThumbnail));
    }

    @Test
    void streamLengthRejectsTruncatedAndNonJpegData() throws IOException {
        byte[] jpeg = sampleJpeg();
        assertEquals(-1, JPEGTools.streamLength(Arrays.copyOf(jpeg, jpeg.length - 2)), "no EOI");
        assertEquals(-1, JPEGTools.streamLength(Arrays.copyOf(jpeg, jpeg.length / 2)), "cut mid-scan");
        assertEquals(-1, JPEGTools.streamLength(new byte[] {0x11, 0x11, 0x11, 0x11, 0x11}), "no SOI");
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
}
