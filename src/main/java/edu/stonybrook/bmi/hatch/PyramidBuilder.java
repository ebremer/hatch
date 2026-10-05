package edu.stonybrook.bmi.hatch;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the reduced levels of an image pyramid while the full-resolution tiles stream in.
 *
 * <p>Each reduced level is a 2x2 box-filtered copy of the level above it, half the size
 * (rounded up). Base tiles must arrive in row-major order. Every two tile rows of one level
 * make one tile row of the next, so each level only holds one row of tiles, as pixels.
 * Reduced levels are computed from those pixels rather than from encoded tiles, so every
 * reduced tile is JPEG-encoded exactly once. Pixels outside the image (the padding of edge
 * tiles) are never sampled: reads are clamped to the image, which also fills the padding of
 * reduced tiles with replicated edge pixels.
 *
 * <p>Decoding, filtering and encoding run on a fixed pool of threads. Encoded tiles are
 * handed to the sink on the calling thread, row by row and left to right within a level.
 */
final class PyramidBuilder implements AutoCloseable {

    /** Receives the encoded tiles of the reduced levels (level 1 and up). */
    interface TileSink {
        void write(int level, int col, int row, byte[] jpeg) throws IOException;
    }

    private final int tileWidth;
    private final int tileHeight;
    private final int baseWidth;
    private final int baseHeight;
    private final int baseAcross;
    private final int baseDown;
    private final float quality;
    private final TileSink sink;
    /** Reduced levels; index 0 (the base, which is not built here) is null. */
    private final Level[] levels;
    private final ExecutorService pool;
    private final List<Future<Void>> baseTasks = new ArrayList<>();
    private int nextCol;
    private int nextRow;

    /**
     * @param width   width of the base level
     * @param height  height of the base level
     * @param levels  number of levels including the base (see {@link #levelCount})
     * @param quality JPEG quality of the reduced levels, 0 &lt; quality &lt;= 1
     * @param threads size of the worker pool
     */
    PyramidBuilder(int width, int height, int tileWidth, int tileHeight, int levels,
                   float quality, int threads, TileSink sink) {
        if (tileWidth < 2 || tileHeight < 2 || tileWidth % 2 != 0 || tileHeight % 2 != 0) {
            throw new IllegalArgumentException("Tile size " + tileWidth + "x" + tileHeight
                + " cannot be halved; tile dimensions must be even");
        }
        if (levels < 1) {
            throw new IllegalArgumentException("A pyramid has at least one level");
        }
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.baseWidth = width;
        this.baseHeight = height;
        this.baseAcross = tilesFor(width, tileWidth);
        this.baseDown = tilesFor(height, tileHeight);
        this.quality = quality;
        this.sink = sink;
        this.levels = new Level[levels];
        int w = width;
        int h = height;
        for (int k = 1; k < levels; k++) {
            w = half(w);
            h = half(h);
            this.levels[k] = new Level(k, w, h);
        }
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "hatch-pyramid-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** One level smaller: half the size, rounding up. */
    static int half(int size) {
        return (size + 1) / 2;
    }

    static int tilesFor(int size, int tileSize) {
        return (size + tileSize - 1) / tileSize;
    }

    /**
     * Number of levels, including the base: the image is halved until it fits in half a
     * tile in both directions.
     */
    static int levelCount(int width, int height, int tileWidth, int tileHeight) {
        int levels = 1;
        while ((width > tileWidth / 2 || height > tileHeight / 2) && (width > 1 || height > 1)) {
            width = half(width);
            height = half(height);
            levels++;
        }
        return levels;
    }

    /** Adds the JPEG-encoded base tile (col, row). */
    void add(int col, int row, byte[] jpeg) throws IOException {
        submitBase(col, row, () -> JPEGTools.decode(jpeg));
    }

    /** Adds a base tile that is already decoded; the image is only read, so it may be shared. */
    void add(int col, int row, BufferedImage image) throws IOException {
        submitBase(col, row, () -> image);
    }

    /** Checks that every tile of every level has been produced. */
    void finish() {
        if (nextRow != baseDown) {
            throw new IllegalStateException("Only " + nextRow + " of " + baseDown + " base tile rows were added");
        }
        for (int k = 1; k < levels.length; k++) {
            if (levels[k].row != levels[k].down) {
                throw new IllegalStateException("Level " + k + " has " + levels[k].row + " of " + levels[k].down + " tile rows");
            }
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private void submitBase(int col, int row, Callable<BufferedImage> source) throws IOException {
        if (col != nextCol || row != nextRow) {
            throw new IllegalStateException("Expected base tile [" + nextRow + "," + nextCol
                + "] but got [" + row + "," + col + "]; tiles must arrive in row-major order");
        }
        if (++nextCol == baseAcross) {
            nextCol = 0;
            nextRow++;
        }
        if (levels.length > 1) {
            Level target = levels[1];
            baseTasks.add(pool.submit(() -> {
                BufferedImage image;
                try {
                    image = source.call();
                } catch (IOException ex) {
                    throw new IOException("Base tile [" + row + "," + col + "]: " + ex.getMessage(), ex);
                }
                int validWidth = Math.min(Math.min(tileWidth, baseWidth - col * tileWidth), image.getWidth());
                int validHeight = Math.min(Math.min(tileHeight, baseHeight - row * tileHeight), image.getHeight());
                reduce(bgr(image), 0, image.getWidth() * 3, validWidth, validHeight,
                    target.strip, target.quadrant(col, row), target.stripWidth * 3);
                return null;
            }));
            if (col == baseAcross - 1) {
                rowDone(1, row, baseDown);
            }
        }
    }

    /**
     * Called when tile row {@code sourceRow} of level k-1 has been reduced into level k.
     * Level k's row is complete after two source rows, or after the last one.
     */
    private void rowDone(int k, int sourceRow, int sourceRows) throws IOException {
        if (k >= levels.length) {
            return;
        }
        boolean bottomHalf = sourceRow % 2 == 1;
        if (!bottomHalf && sourceRow != sourceRows - 1) {
            return;
        }
        if (k == 1) {
            await(baseTasks);
            baseTasks.clear();
        }
        Level level = levels[k];
        if (!bottomHalf) {
            level.repeatTopHalf();
        }
        emit(level);
    }

    /** Encodes and hands out the completed row of a level, and reduces it into the next. */
    private void emit(Level level) throws IOException {
        Level next = level.index + 1 < levels.length ? levels[level.index + 1] : null;
        int row = level.row;
        List<Future<byte[]>> tiles = new ArrayList<>(level.across);
        for (int col = 0; col < level.across; col++) {
            int c = col;
            tiles.add(pool.submit(() -> {
                if (next != null) {
                    int validWidth = Math.min(tileWidth, level.width - c * tileWidth);
                    int validHeight = Math.min(tileHeight, level.height - row * tileHeight);
                    reduce(level.strip, c * tileWidth * 3, level.stripWidth * 3, validWidth, validHeight,
                        next.strip, next.quadrant(c, row), next.stripWidth * 3);
                }
                return JPEGTools.encode(level.tile(c), quality);
            }));
        }
        List<byte[]> encoded = await(tiles);
        for (int col = 0; col < encoded.size(); col++) {
            sink.write(level.index, col, row, encoded.get(col));
        }
        level.row++;
        rowDone(level.index + 1, row, level.down);
    }

    /**
     * 2x2 box filter: writes a (tileWidth/2) x (tileHeight/2) block at {@code dst[dstOffset]}
     * from the top-left {@code validWidth} x {@code validHeight} pixels of the source block at
     * {@code src[srcOffset]}. Reads are clamped to that region, so output pixels beyond it
     * repeat its last column and row. Pixels are 3 bytes; strides are in bytes.
     */
    private void reduce(byte[] src, int srcOffset, int srcStride, int validWidth, int validHeight,
                        byte[] dst, int dstOffset, int dstStride) {
        int outWidth = tileWidth / 2;
        int outHeight = tileHeight / 2;
        int[] left = new int[outWidth];
        int[] right = new int[outWidth];
        for (int x = 0; x < outWidth; x++) {
            left[x] = Math.min(2 * x, validWidth - 1) * 3;
            right[x] = Math.min(2 * x + 1, validWidth - 1) * 3;
        }
        for (int y = 0; y < outHeight; y++) {
            int top = srcOffset + Math.min(2 * y, validHeight - 1) * srcStride;
            int bottom = srcOffset + Math.min(2 * y + 1, validHeight - 1) * srcStride;
            int d = dstOffset + y * dstStride;
            for (int x = 0; x < outWidth; x++) {
                int a = top + left[x];
                int b = top + right[x];
                int c = bottom + left[x];
                int e = bottom + right[x];
                dst[d++] = (byte) (((src[a] & 0xff) + (src[b] & 0xff) + (src[c] & 0xff) + (src[e] & 0xff) + 2) >> 2);
                dst[d++] = (byte) (((src[a + 1] & 0xff) + (src[b + 1] & 0xff) + (src[c + 1] & 0xff) + (src[e + 1] & 0xff) + 2) >> 2);
                dst[d++] = (byte) (((src[a + 2] & 0xff) + (src[b + 2] & 0xff) + (src[c + 2] & 0xff) + (src[e + 2] & 0xff) + 2) >> 2);
            }
        }
    }

    /** The pixels of an image as 3-byte BGR rows of width*3 bytes, without copying when possible. */
    static byte[] bgr(BufferedImage image) {
        WritableRaster raster = image.getRaster();
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR
                && raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0
                && raster.getDataBuffer().getOffset() == 0
                && ((DataBufferByte) raster.getDataBuffer()).getData().length == image.getWidth() * image.getHeight() * 3) {
            return ((DataBufferByte) raster.getDataBuffer()).getData();
        }
        // e.g. TYPE_CUSTOM from a JPEG with an ICC profile: convert to plain BGR
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return ((DataBufferByte) copy.getRaster().getDataBuffer()).getData();
    }

    private static <T> List<T> await(List<Future<T>> futures) throws IOException {
        List<T> results = new ArrayList<>(futures.size());
        try {
            for (Future<T> f : futures) {
                results.add(f.get());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Pyramid generation was interrupted");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw new IOException("Pyramid generation failed: " + cause, cause);
        }
        return results;
    }

    /** A reduced level, with the one row of tiles being assembled. */
    private final class Level {
        final int index;
        final int width;
        final int height;
        final int across;
        final int down;
        /** Width in pixels of the row buffer: a whole number of tiles. */
        final int stripWidth;
        /** The tile row being assembled, as BGR pixels, stripWidth x tileHeight. */
        final byte[] strip;
        /** Index of the tile row being assembled. */
        int row;

        Level(int index, int width, int height) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.across = tilesFor(width, tileWidth);
            this.down = tilesFor(height, tileHeight);
            this.stripWidth = across * tileWidth;
            this.strip = new byte[Math.multiplyExact(Math.multiplyExact(stripWidth, tileHeight), 3)];
        }

        /** Byte offset of the quarter of this row that source tile (col, row) of the level above fills. */
        int quadrant(int sourceCol, int sourceRow) {
            int x = sourceCol * (tileWidth / 2);
            int y = (sourceRow % 2) * (tileHeight / 2);
            return (y * stripWidth + x) * 3;
        }

        /** Fills the bottom half, which no source row reached, with the last row of the top half. */
        void repeatTopHalf() {
            int rowBytes = stripWidth * 3;
            int last = (tileHeight / 2 - 1) * rowBytes;
            for (int y = tileHeight / 2; y < tileHeight; y++) {
                System.arraycopy(strip, last, strip, y * rowBytes, rowBytes);
            }
        }

        /** Tile {@code col} of the row being assembled, as its own image. */
        BufferedImage tile(int col) {
            BufferedImage tile = new BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_3BYTE_BGR);
            byte[] data = ((DataBufferByte) tile.getRaster().getDataBuffer()).getData();
            int rowBytes = tileWidth * 3;
            for (int y = 0; y < tileHeight; y++) {
                System.arraycopy(strip, (y * stripWidth + col * tileWidth) * 3, data, y * rowBytes, rowBytes);
            }
            return tile;
        }
    }
}
