package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import loci.formats.FormatException;
import loci.formats.tiff.PhotoInterp;

/**
 * Generates small, self-contained tiled JPEG TIFF "slides" usable as Hatch input.
 *
 * <p>The fixtures are written with {@link HatchWriter} (the same writer Hatch uses
 * for its output) so they land in exactly the dialect the {@code .tif} reading path
 * consumes: tiled, JPEG-compressed (tag 7), one self-contained JPEG per tile, and
 * crucially NO {@code JPEG_TABLES} tag — which is the branch {@code TiffParser.getRawTile}
 * reads verbatim. Slides are synthesized at test time (no binary blobs committed).
 */
final class TestFixtures {

    private TestFixtures() {
    }

    /** The pyramid depth (== number of output images) Hatch computes for this geometry. */
    static int expectedDepth(int width, int height, int tileSize) {
        int size = Math.max(width, height);
        int ss = (int) Math.ceil(Math.log(size) / Math.log(2));
        int tiless = (int) Math.ceil(Math.log(tileSize) / Math.log(2));
        return ss - tiless + 2;
    }

    /**
     * Offsets of every IFD in a little-endian BigTIFF, read straight from the bytes (independent
     * of the project's parser). Fails if the chain points outside the file or does not end in 0.
     */
    static List<Long> ifdChain(File tiff) throws IOException {
        byte[] bytes = Files.readAllBytes(tiff.toPath());
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(0) != 0x4949 || b.getShort(2) != 43) {
            throw new IOException("not a little-endian BigTIFF: " + tiff);
        }
        List<Long> offsets = new ArrayList<>();
        long off = b.getLong(8);
        while (off != 0) {
            if (off < 16 || off + 8 > bytes.length || offsets.size() > 64) {
                throw new IOException("IFD offset " + off + " is outside the " + bytes.length + "-byte file");
            }
            offsets.add(off);
            long entries = b.getLong((int) off);
            long next = off + 8 + entries * 20;
            if (next + 8 > bytes.length) {
                throw new IOException("IFD at " + off + " runs past the end of the file");
            }
            off = b.getLong((int) next);
        }
        return offsets;
    }

    /** Number of tiles needed to cover {@code dim} pixels at {@code tileSize} (ceiling). */
    static int tileCount(int dim, int tileSize) {
        int n = dim / tileSize;
        if (n * tileSize != dim) {
            n++;
        }
        return n;
    }

    /** Writes a single-image, tiled, JPEG-compressed TIFF of the given logical size. */
    static void writeSlide(File dest, int width, int height, int tileSize)
            throws IOException, FormatException {
        int nX = tileCount(width, tileSize);
        int nY = tileCount(height, tileSize);
        int numtiles = nX * nY;

        loci.formats.tiff.IFD ifd = new loci.formats.tiff.IFD();
        ifd.put(IFD.RESOLUTION_UNIT, 3);
        ifd.put(IFD.TILE_WIDTH, tileSize);
        ifd.put(IFD.TILE_LENGTH, tileSize);
        ifd.put(IFD.IMAGE_WIDTH, (long) width);
        ifd.put(IFD.IMAGE_LENGTH, (long) height);
        ifd.put(IFD.TILE_OFFSETS, new long[numtiles]);
        ifd.put(IFD.TILE_BYTE_COUNTS, new long[numtiles]);
        ifd.put(IFD.COMPRESSION, 7);
        ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 8, 8});
        ifd.put(IFD.SAMPLES_PER_PIXEL, 3);
        ifd.put(IFD.PLANAR_CONFIGURATION, 1);
        ifd.put(IFD.SAMPLE_FORMAT, new int[] {1, 1, 1});
        ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
        ifd.put(IFD.Y_CB_CR_SUB_SAMPLING, new int[] {1, 1});

        try (HatchWriter writer = new HatchWriter(dest.toString())) {
            for (int y = 0; y < nY; y++) {
                for (int x = 0; x < nX; x++) {
                    boolean last = (y == nY - 1) && (x == nX - 1);
                    byte[] jpeg = jpegTile(x, y, tileSize);
                    writer.writeIFDStrips(ifd, jpeg, last, x * tileSize, y * tileSize);
                }
            }
        }
    }

    /** A full-size tile with distinct, non-uniform content so the JPEG has real structure. */
    private static byte[] jpegTile(int tx, int ty, int tileSize) throws IOException {
        BufferedImage bi = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color((tx * 53 + 30) % 256, (ty * 97 + 60) % 256, 128));
        g.fillRect(0, 0, tileSize, tileSize);
        g.setColor(Color.WHITE);
        for (int i = 0; i < tileSize; i += 16) {
            g.drawLine(0, i, tileSize, i);
            g.drawLine(i, 0, i, tileSize);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpeg", baos);
        return baos.toByteArray();
    }
}
