package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Unit tests for the pyramid tile-grid and dimension arithmetic. */
class PyramidTest {

    private static HatchParameters params() {
        HatchParameters p = new HatchParameters();
        p.quality = 0.8f;
        return p;
    }

    private static BufferedImage tile(int size, Color c) {
        BufferedImage bi = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.drawLine(0, 0, size, size);
        g.dispose();
        return bi;
    }

    static void assertJpeg(byte[] b) {
        assertNotNull(b, "tile bytes");
        assertTrue(b.length > 4, "non-trivial JPEG");
        assertEquals((byte) 0xFF, b[0], "SOI byte 0");
        assertEquals((byte) 0xD8, b[1], "SOI byte 1");
        assertEquals((byte) 0xFF, b[b.length - 2], "EOI byte 0");
        assertEquals((byte) 0xD9, b[b.length - 1], "EOI byte 1");
    }

    @Test
    void lumpHalvesTileGridWithCeiling() {
        int ts = 64;
        Pyramid p = new Pyramid(params(), 3, 2, ts, ts, 160, 100);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                p.put(tile(ts, new Color(20 + x * 30, 40 + y * 30, 80)), x, y);
            }
        }

        p.Lump();

        assertEquals(2, p.gettilesX(), "ceil(3/2) tiles across");
        assertEquals(1, p.gettilesY(), "ceil(2/2) tiles down");
        // merged (pre-Shrink) tiles span a 2x2 block of source tiles
        assertEquals(2 * ts, p.getBufferedImage(0, 0).getWidth(), "merged tile width");
        assertEquals(2 * ts, p.getBufferedImage(1, 0).getWidth(), "edge merged tile still full width");
    }

    @Test
    void shrinkHalvesLogicalDimensions() {
        int ts = 64;
        Pyramid p = new Pyramid(params(), 2, 2, ts, ts, 200, 100);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                p.put(tile(ts, new Color(10, 20 * (x + 1), 30 * (y + 1))), x, y);
            }
        }

        p.Lump();
        p.Shrink();

        assertEquals(100, p.getWidth(), "width halved (round(200*0.5))");
        assertEquals(50, p.getHeight(), "height halved (round(100*0.5))");
        assertJpeg(p.GetImageBytes(0, 0));
    }

    @Test
    void oddDimensionsRoundOnShrink() {
        int ts = 64;
        Pyramid p = new Pyramid(params(), 2, 2, ts, ts, 201, 99);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                p.put(tile(ts, new Color(60, 60, 60)), x, y);
            }
        }

        p.Lump();
        p.Shrink();

        assertEquals(Math.round(201 * 0.5), p.getWidth(), "round(201*0.5)==101");
        assertEquals(Math.round(99 * 0.5), p.getHeight(), "round(99*0.5)==50");
    }

    @Test
    void lumpSurfacesTileFailuresInsteadOfSwallowing() {
        // Regression for the fix: a missing/failed tile must make Lump fail loudly
        // (via the awaited futures), not silently produce a broken pyramid level.
        Pyramid p = new Pyramid(params(), 2, 2, 64, 64, 128, 128);
        // intentionally leave all tiles null
        assertThrows(RuntimeException.class, p::Lump,
            "Lump must propagate a failed merge, not swallow it");
    }
}
