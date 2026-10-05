package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Unit tests for the streaming pyramid builder: level geometry, filtering, edges, ordering, failures. */
class PyramidTest {

    private static final int TILE = 64;

    /** Collects the tiles a builder emits, in emission order. */
    private static final class Sink implements PyramidBuilder.TileSink {
        final List<String> order = new ArrayList<>();
        final Map<String, byte[]> tiles = new HashMap<>();

        @Override
        public void write(int level, int col, int row, byte[] jpeg) {
            String key = level + ":" + col + "," + row;
            order.add(key);
            tiles.put(key, jpeg);
        }

        BufferedImage image(int level, int col, int row) throws IOException {
            return JPEGTools.decode(tiles.get(level + ":" + col + "," + row));
        }
    }

    private static BufferedImage solid(int width, int height, Color c) {
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bi.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return bi;
    }

    /** Builds a pyramid whose base tile (col,row) comes from {@code tile}, encoded as JPEG. */
    private static Sink build(int width, int height, TileSource tile) throws IOException {
        int levels = PyramidBuilder.levelCount(width, height, TILE, TILE);
        Sink sink = new Sink();
        try (PyramidBuilder b = new PyramidBuilder(width, height, TILE, TILE, levels, 1.0f, 3, sink)) {
            for (int row = 0; row < PyramidBuilder.tilesFor(height, TILE); row++) {
                for (int col = 0; col < PyramidBuilder.tilesFor(width, TILE); col++) {
                    b.add(col, row, JPEGTools.encode(tile.get(col, row), 1.0f));
                }
            }
            b.finish();
        }
        return sink;
    }

    private interface TileSource {
        BufferedImage get(int col, int row);
    }

    private static void assertColour(Color expected, int rgb, int tolerance, String what) {
        assertEquals(expected.getRed(), (rgb >> 16) & 0xff, tolerance, what + " red");
        assertEquals(expected.getGreen(), (rgb >> 8) & 0xff, tolerance, what + " green");
        assertEquals(expected.getBlue(), rgb & 0xff, tolerance, what + " blue");
    }

    @Test
    void levelCountKeepsTheExistingRuleForPowerOfTwoTiles() {
        // the old floating-point rule: ceil(log2(max side)) - log2(tile) + 2
        for (int size : new int[] {100, 128, 129, 200, 255, 256, 257, 480, 511, 512, 513, 640, 1000, 4096, 4097, 100000}) {
            int old = (int) Math.ceil(Math.log(size) / Math.log(2)) - 8 + 2;
            assertEquals(Math.max(1, old), PyramidBuilder.levelCount(size, size / 2, 256, 256), "size " + size);
        }
    }

    @Test
    void levelCountUsesBothTileDimensions() {
        // 256 wide fits half a 512-wide tile, but 1000 high needs halving until <= 64
        assertEquals(5, PyramidBuilder.levelCount(256, 1000, 512, 128));
        assertEquals(1, PyramidBuilder.levelCount(1, 1, 2, 2));
    }

    @Test
    void everyLevelGetsEveryTileOnceInRowMajorOrder() throws Exception {
        // 5x3 base tiles -> 3x2 -> 2x1 -> 1x1 ...
        int width = 5 * TILE - 10;
        int height = 3 * TILE - 7;
        Sink sink = build(width, height, (c, r) -> solid(TILE, TILE, new Color(40 * c, 60 * r, 90)));

        int levels = PyramidBuilder.levelCount(width, height, TILE, TILE);
        int w = width;
        int h = height;
        List<String> expected = new ArrayList<>();
        for (int k = 1; k < levels; k++) {
            w = PyramidBuilder.half(w);
            h = PyramidBuilder.half(h);
            for (int r = 0; r < PyramidBuilder.tilesFor(h, TILE); r++) {
                for (int c = 0; c < PyramidBuilder.tilesFor(w, TILE); c++) {
                    expected.add(k + ":" + c + "," + r);
                }
            }
        }
        List<String> sorted = new ArrayList<>(sink.order);
        sorted.sort(null);
        List<String> expectedSorted = new ArrayList<>(expected);
        expectedSorted.sort(null);
        assertEquals(expectedSorted, sorted, "each reduced tile exactly once");
        for (int k = 1; k < levels; k++) {
            int level = k;
            List<String> ofLevel = sink.order.stream().filter(s -> s.startsWith(level + ":")).toList();
            List<String> expectedOfLevel = expected.stream().filter(s -> s.startsWith(level + ":")).toList();
            assertEquals(expectedOfLevel, ofLevel, "level " + k + " tiles arrive in row-major order");
        }
        for (byte[] jpeg : sink.tiles.values()) {
            assertEquals(new JPEGTools.JpegInfo(3, 2, 2, false), JPEGTools.inspect(jpeg),
                "reduced tiles are JFIF YCbCr 4:2:0, as the reduced-level IFDs declare");
        }
    }

    @Test
    void eachBaseTileBecomesOneQuarterOfTheNextLevel() throws IOException {
        Color[][] colours = {
            {new Color(200, 30, 30), new Color(30, 200, 30)},
            {new Color(30, 30, 200), new Color(220, 220, 40)},
        };
        Sink sink = build(2 * TILE, 2 * TILE, (c, r) -> solid(TILE, TILE, colours[r][c]));

        BufferedImage level1 = sink.image(1, 0, 0);
        int q = TILE / 4;
        assertColour(colours[0][0], level1.getRGB(q, q), 3, "top-left quarter");
        assertColour(colours[0][1], level1.getRGB(3 * q, q), 3, "top-right quarter");
        assertColour(colours[1][0], level1.getRGB(q, 3 * q), 3, "bottom-left quarter");
        assertColour(colours[1][1], level1.getRGB(3 * q, 3 * q), 3, "bottom-right quarter");
    }

    @Test
    void paddingOutsideTheImageNeverBleedsIntoReducedLevels() throws IOException {
        // odd width and height: the last column/row of each reduced level pairs an image pixel
        // with padding. The padding is black; the image is white.
        int width = TILE + 45;
        int height = TILE + 21;
        Sink sink = build(width, height, (c, r) -> {
            BufferedImage bi = solid(TILE, TILE, Color.BLACK);
            int w = Math.min(TILE, width - c * TILE);
            int h = Math.min(TILE, height - r * TILE);
            Graphics2D g = bi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.dispose();
            return bi;
        });

        int w = width;
        int h = height;
        int levels = PyramidBuilder.levelCount(width, height, TILE, TILE);
        assertTrue(levels >= 3);
        for (int k = 1; k < levels; k++) {
            w = PyramidBuilder.half(w);
            h = PyramidBuilder.half(h);
            int lastX = w - 1;
            int lastY = h - 1;
            BufferedImage corner = sink.image(k, lastX / TILE, lastY / TILE);
            assertColour(Color.WHITE, corner.getRGB(lastX % TILE, lastY % TILE), 4,
                "level " + k + " bottom-right image pixel");
        }
    }

    @Test
    void undecodableBaseTileFailsWithItsPosition() {
        Sink sink = new Sink();
        IOException ex = assertThrows(IOException.class, () -> {
            try (PyramidBuilder b = new PyramidBuilder(2 * TILE, TILE, TILE, TILE, 2, 0.8f, 2, sink)) {
                b.add(0, 0, JPEGTools.encode(solid(TILE, TILE, Color.GRAY), 0.8f));
                b.add(1, 0, new byte[] {1, 2, 3, 4});
                b.finish();
            }
        });
        assertTrue(ex.getMessage().contains("[0,1]"), ex.getMessage());
    }

    @Test
    void tilesMustArriveInRowMajorOrder() throws IOException {
        try (PyramidBuilder b = new PyramidBuilder(2 * TILE, 2 * TILE, TILE, TILE, 2, 0.8f, 1, new Sink())) {
            BufferedImage tile = solid(TILE, TILE, Color.GRAY);
            b.add(0, 0, tile);
            assertThrows(IllegalStateException.class, () -> b.add(0, 1, tile));
        }
    }

    @Test
    void oddTileDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PyramidBuilder(100, 100, 63, 64, 2, 0.8f, 1, new Sink()));
    }
}
