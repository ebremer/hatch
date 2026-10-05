package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.OnDemandLongArray;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffParser;
import loci.formats.tiff.TiffRational;

/**
 * Generates small, self-contained tiled TIFF "slides" usable as Hatch input.
 *
 * <p>The fixtures are written with {@link TiledTiffWriter} (the same writer Hatch uses
 * for its output) so they land in exactly the dialect the {@code .tif} reading path
 * consumes: tiled, JPEG-compressed (tag 7), one self-contained JPEG per tile, and
 * NO {@code JPEG_TABLES} tag, so tiles are read verbatim. Slides are synthesized at test
 * time (no binary blobs committed).
 */
final class TestFixtures {

    /**
     * One image of a fixture TIFF.
     *
     * @param compression     TIFF compression code: 7 (JPEG tiles) or 1 (uncompressed tiles)
     * @param samples         samples per pixel: 3 (RGB) or 1 (grayscale)
     * @param skipped         indices of tiles that are not stored (sparse file)
     * @param micronsPerPixel physical pixel size to record, or null for an uncalibrated image
     */
    record Image(int width, int height, int tileSize, int compression, int samples,
                 Set<Integer> skipped, Double micronsPerPixel) {

        static Image jpeg(int width, int height, int tileSize) {
            return new Image(width, height, tileSize, 7, 3, Set.of(), null);
        }

        Image skipping(Integer... tiles) {
            return new Image(width, height, tileSize, compression, samples, Set.of(tiles), micronsPerPixel);
        }

        Image calibrated(double micronsPerPixel) {
            return new Image(width, height, tileSize, compression, samples, skipped, micronsPerPixel);
        }

        Image gray() {
            return new Image(width, height, tileSize, compression, 1, skipped, micronsPerPixel);
        }

        Image uncompressed() {
            return new Image(width, height, tileSize, 1, samples, skipped, micronsPerPixel);
        }
    }

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

    /** Parsed IFDs of a TIFF, read with Bio-Formats' own parser. */
    static IFDList ifds(File tiff) throws IOException, FormatException {
        try (RandomAccessInputStream in = new RandomAccessInputStream(tiff.toString())) {
            IFDList ifds = new TiffParser(in).getMainIFDs();
            for (IFD ifd : ifds) {
                // arrays read on demand need the stream, which is about to close
                for (int tag : new int[] {IFD.TILE_OFFSETS, IFD.TILE_BYTE_COUNTS, IFD.STRIP_OFFSETS, IFD.STRIP_BYTE_COUNTS}) {
                    if (ifd.get(tag) instanceof OnDemandLongArray lazy) {
                        ifd.put(tag, lazy.toArray());
                    }
                }
            }
            return ifds;
        }
    }

    /** Tile (row, col) of IFD {@code image} as a standalone JPEG, or null if it is not stored. */
    static byte[] rawTile(File tiff, int image, int row, int col) throws IOException, FormatException {
        try (RandomAccessInputStream in = new RandomAccessInputStream(tiff.toString());
             TileFile file = new TileFile(tiff.toString())) {
            IFD ifd = new TiffParser(in).getMainIFDs().get(image);
            return new JpegTiffTiles(file, in, ifd).read(row, col);
        }
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
        write(dest, Image.jpeg(width, height, tileSize));
    }

    /** Writes the images, in order, as the IFDs of one TIFF. */
    static void write(File dest, Image... images) throws IOException, FormatException {
        try (TiledTiffWriter writer = new TiledTiffWriter(dest.toString())) {
            for (Image image : images) {
                writeImage(writer, image);
            }
            writer.finish();
        }
    }

    private static void writeImage(TiledTiffWriter writer, Image im)
            throws IOException, FormatException {
        int nX = tileCount(im.width(), im.tileSize());
        int nY = tileCount(im.height(), im.tileSize());

        IFD ifd = new IFD();
        ifd.put(IFD.TILE_WIDTH, im.tileSize());
        ifd.put(IFD.TILE_LENGTH, im.tileSize());
        ifd.put(IFD.IMAGE_WIDTH, (long) im.width());
        ifd.put(IFD.IMAGE_LENGTH, (long) im.height());
        ifd.put(IFD.COMPRESSION, im.compression());
        ifd.put(IFD.SAMPLES_PER_PIXEL, im.samples());
        ifd.put(IFD.PLANAR_CONFIGURATION, 1);
        if (im.samples() == 3) {
            ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 8, 8});
            ifd.put(IFD.SAMPLE_FORMAT, new int[] {1, 1, 1});
        } else {
            ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8});
            ifd.put(IFD.SAMPLE_FORMAT, new int[] {1});
        }
        if (im.samples() == 1) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.BLACK_IS_ZERO.getCode());
        } else if (im.compression() == 7) {
            // ImageIO writes JFIF YCbCr 4:2:0
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            ifd.put(IFD.Y_CB_CR_SUB_SAMPLING, new int[] {2, 2});
        } else {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.RGB.getCode());
        }
        if (im.micronsPerPixel() != null) {
            long pixelsPerCm = Math.round(10000 / im.micronsPerPixel());
            ifd.put(IFD.RESOLUTION_UNIT, 3);
            ifd.put(IFD.X_RESOLUTION, new TiffRational(pixelsPerCm, 1));
            ifd.put(IFD.Y_RESOLUTION, new TiffRational(pixelsPerCm, 1));
        }

        TiledTiffWriter.Image out = writer.addImage(ifd);
        for (int y = 0; y < nY; y++) {
            for (int x = 0; x < nX; x++) {
                int index = y * nX + x;
                if (im.skipped().contains(index)) {
                    continue;
                }
                BufferedImage tile = tileImage(x, y, im.tileSize(), im.samples());
                byte[] data = im.compression() == 7 ? jpeg(tile) : rgbSamples(tile);
                out.writeTile(x, y, data);
            }
        }
    }

    /** A full-size tile with distinct, non-uniform content so the JPEG has real structure. */
    private static BufferedImage tileImage(int tx, int ty, int tileSize, int samples) {
        BufferedImage bi = new BufferedImage(tileSize, tileSize,
            samples == 3 ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color((tx * 53 + 30) % 256, (ty * 97 + 60) % 256, 128));
        g.fillRect(0, 0, tileSize, tileSize);
        g.setColor(Color.WHITE);
        for (int i = 0; i < tileSize; i += 16) {
            g.drawLine(0, i, tileSize, i);
            g.drawLine(i, 0, i, tileSize);
        }
        g.dispose();
        return bi;
    }

    /** Uncompressed tile samples: TYPE_3BYTE_BGR stores B,G,R but TIFF wants R,G,B. */
    private static byte[] rgbSamples(BufferedImage bi) {
        byte[] p = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData().clone();
        if (bi.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            for (int i = 0; i < p.length; i += 3) {
                byte t = p[i];
                p[i] = p[i + 2];
                p[i + 2] = t;
            }
        }
        return p;
    }

    private static byte[] jpeg(BufferedImage bi) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpeg", baos);
        return baos.toByteArray();
    }
}
