package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import loci.common.RandomAccessOutputStream;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffSaver;

/**
 * Writes a little-endian BigTIFF of tiled images.
 *
 * <p>Tiles are appended to the file as they arrive, in any order and for any image, while
 * their offsets and byte counts are kept in memory. {@link #finish()} then writes each
 * image's IFD exactly once, so the cost is linear in the number of tiles. Tiles that are
 * never written are recorded with offset and byte count 0 (a sparse tile).
 *
 * <p>Not thread-safe: one thread writes the file.
 */
public class TiledTiffWriter implements AutoCloseable {
    /** Position of the first-IFD offset in a BigTIFF header. */
    private static final long FIRST_IFD_POINTER = 8;

    private final RandomAccessOutputStream out;
    private final TiffSaver saver;
    private final List<Image> images = new ArrayList<>();
    private boolean finished;

    public TiledTiffWriter(String file) throws IOException {
        out = new RandomAccessOutputStream(file);
        try {
            saver = new TiffSaver(out, file);
            saver.setBigTiff(true);
            saver.setLittleEndian(true);
            saver.writeHeader();
        } catch (IOException | RuntimeException ex) {
            try {
                out.close();
            } catch (IOException e) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    /**
     * Adds an image. Its IFD must give the image and tile dimensions; the tile offset and
     * byte count tags are filled in by {@link #finish()}. Images are chained in the order
     * they are added.
     */
    public Image addImage(IFD ifd) throws FormatException {
        if (finished) {
            throw new IllegalStateException("The file is already finished");
        }
        Image image = new Image(ifd);
        images.add(image);
        return image;
    }

    /**
     * Writes the IFDs and links them into the chain. The IFDs are written last-first, so each
     * one is written once, already pointing at its successor; the header is pointed at the
     * first one at the end.
     */
    public void finish() throws FormatException, IOException {
        if (finished) {
            return;
        }
        if (images.isEmpty()) {
            throw new IllegalStateException("A TIFF needs at least one image");
        }
        long next = 0;
        for (int i = images.size() - 1; i >= 0; i--) {
            Image image = images.get(i);
            image.ifd.put(IFD.TILE_OFFSETS, image.offsets);
            image.ifd.put(IFD.TILE_BYTE_COUNTS, image.byteCounts);
            long position = out.length();
            if (position % 2 != 0) {
                // IFDs start on a word boundary
                out.seek(position);
                out.writeByte(0);
                position++;
            }
            out.seek(position);
            saver.writeIFD(image.ifd, next);
            next = position;
        }
        out.seek(FIRST_IFD_POINTER);
        out.writeLong(next);
        finished = true;
    }

    /** Flushes and closes the file; a file closed before {@link #finish()} is incomplete. */
    @Override
    public void close() throws IOException {
        try (out; saver) {
            out.flush();
        }
    }

    /** One tiled image of the file. */
    public final class Image {
        private final IFD ifd;
        private final int tilesAcross;
        private final int tilesDown;
        private final long[] offsets;
        private final long[] byteCounts;

        private Image(IFD ifd) throws FormatException {
            this.ifd = ifd;
            long across = ifd.getTilesPerRow();
            long down = ifd.getTilesPerColumn();
            if (across < 1 || down < 1 || across * down > Integer.MAX_VALUE) {
                throw new FormatException("Invalid tile grid " + across + " x " + down);
            }
            tilesAcross = (int) across;
            tilesDown = (int) down;
            offsets = new long[tilesAcross * tilesDown];
            byteCounts = new long[offsets.length];
        }

        public int tilesAcross() {
            return tilesAcross;
        }

        public int tilesDown() {
            return tilesDown;
        }

        /** Appends tile (col, row) to the file. */
        public void writeTile(int col, int row, byte[] data) throws IOException {
            if (finished) {
                throw new IllegalStateException("The file is already finished");
            }
            if (col < 0 || row < 0 || col >= tilesAcross || row >= tilesDown) {
                throw new IllegalArgumentException("Tile [" + row + "," + col + "] is outside the "
                    + tilesDown + "x" + tilesAcross + " tile grid");
            }
            if (data.length == 0) {
                throw new IllegalArgumentException("Tile [" + row + "," + col + "] is empty");
            }
            int index = row * tilesAcross + col;
            if (byteCounts[index] != 0) {
                throw new IllegalStateException("Tile [" + row + "," + col + "] was already written");
            }
            long position = out.length();
            out.seek(position);
            out.write(data);
            offsets[index] = position;
            byteCounts[index] = data.length;
        }
    }
}
