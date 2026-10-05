package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.OnDemandLongArray;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffCompression;

/**
 * Reads the JPEG tiles of one TIFF image (IFD) as standalone JPEG streams, without decoding
 * them. The tile offsets and byte counts are resolved once, so each tile read costs one
 * positional read.
 *
 * <p>Abbreviated tiles that share a JPEGTables tag get the tables spliced in, followed by an
 * Adobe APP14 marker recording the colour transform the Photometric tag implies; the
 * tables carry no colour information, and a standalone decoder would otherwise guess.
 */
final class JpegTiffTiles {
    private static final byte[] APP14_RGB = adobe(0);
    private static final byte[] APP14_YCBCR = adobe(1);

    private final TileFile file;
    private final IFD ifd;
    private final int across;
    private final int down;
    private final long[] offsets;
    private final long[] byteCounts;
    /** The JPEG tables without their EOI marker, plus an APP14 marker; null if tiles are self-contained. */
    private final byte[] prefix;

    /**
     * @param file the TIFF, for the tile reads
     * @param in   a stream on the TIFF, for offset and byte count arrays that are read on demand
     */
    JpegTiffTiles(TileFile file, RandomAccessInputStream in, IFD ifd) throws FormatException, IOException {
        this.file = file;
        this.ifd = ifd;
        long a = ifd.getTilesPerRow();
        long d = ifd.getTilesPerColumn();
        if (a < 1 || d < 1 || a * d > Integer.MAX_VALUE) {
            throw new FormatException("Invalid tile grid " + a + " x " + d);
        }
        across = (int) a;
        down = (int) d;
        offsets = longs(in, ifd.containsKey(IFD.TILE_OFFSETS) ? IFD.TILE_OFFSETS : IFD.STRIP_OFFSETS);
        byteCounts = longs(in, ifd.containsKey(IFD.TILE_BYTE_COUNTS) ? IFD.TILE_BYTE_COUNTS : IFD.STRIP_BYTE_COUNTS);
        if (offsets == null || byteCounts == null || offsets.length < across * down || byteCounts.length < across * down) {
            throw new FormatException("The IFD lists " + (offsets == null ? 0 : offsets.length) + " tile offsets and "
                + (byteCounts == null ? 0 : byteCounts.length) + " byte counts for a " + across + "x" + down + " tile grid");
        }
        prefix = tablesPrefix(ifd);
    }

    /**
     * Checks that the image can be copied tile by tile: JPEG-compressed, tiled, interleaved,
     * 8-bit RGB.
     *
     * @return its tile grid, with white as the background for tiles that are not stored
     */
    static RawTileLayout layout(IFD ifd) throws FormatException {
        TiffCompression compression = ifd.getCompression();
        if (compression != TiffCompression.JPEG) {
            throw new FormatException("Tiles are " + compression.getCodecName() + " (TIFF compression " +
                compression.getCode() + "); only JPEG (compression 7) tiles can be copied");
        }
        if (!ifd.isTiled()) {
            throw new FormatException("Image is stored in strips, not tiles");
        }
        if (ifd.getPlanarConfiguration() != 1) {
            throw new FormatException("Planar configuration " + ifd.getPlanarConfiguration() +
                " is not supported; samples must be interleaved");
        }
        if (ifd.getSamplesPerPixel() != 3 || ifd.getBitsPerSample()[0] != 8) {
            throw new FormatException(ifd.getSamplesPerPixel() + " x " + ifd.getBitsPerSample()[0] +
                "-bit samples per pixel; only 8-bit RGB images are supported");
        }
        return new RawTileLayout((int) ifd.getImageWidth(), (int) ifd.getImageLength(),
            (int) ifd.getTileWidth(), (int) ifd.getTileLength(),
            (int) ifd.getTilesPerRow(), (int) ifd.getTilesPerColumn(), RawTileLayout.WHITE);
    }

    IFD ifd() {
        return ifd;
    }

    /** Tile (row, col) as a standalone JPEG, or null if the file does not store it (byte count 0). */
    byte[] read(int row, int col) throws FormatException, IOException {
        if (row < 0 || col < 0 || row >= down || col >= across) {
            throw new FormatException("Tile [" + row + "," + col + "] is outside the " + down + "x" + across + " tile grid");
        }
        int index = row * across + col;
        long offset = offsets[index];
        long count = byteCounts[index];
        if (count == 0) {
            return null; // sparse file: this tile was never written
        }
        if (count < 2 || count > Integer.MAX_VALUE - 64 || offset < 0 || offset > file.length() - count) {
            throw new FormatException("Tile [" + row + "," + col + "] has " + count + " bytes at offset " + offset
                + ", outside the " + file.length() + "-byte file; the file may be truncated");
        }
        if (prefix == null) {
            return file.read(offset, (int) count);
        }
        // the tables without their EOI, the APP14 marker, then the tile without its SOI:
        // read the whole tile so that its SOI lands on the last two bytes of the prefix
        byte[] tile = new byte[prefix.length - 2 + (int) count];
        file.read(offset, tile, prefix.length - 2, (int) count);
        if ((tile[prefix.length - 2] & 0xff) != 0xFF || (tile[prefix.length - 1] & 0xff) != 0xD8) {
            throw new FormatException("Tile [" + row + "," + col + "] at offset " + offset + " does not start with a JPEG SOI marker");
        }
        System.arraycopy(prefix, 0, tile, 0, prefix.length);
        return tile;
    }

    /** Checks that every tile is stored and lies inside the file. */
    void checkAllStored() throws FormatException {
        for (int i = 0; i < across * down; i++) {
            if (byteCounts[i] <= 0) {
                throw new FormatException("Tile [" + i / across + "," + i % across + "] is not stored");
            }
            if (offsets[i] < 0 || offsets[i] > file.length() - byteCounts[i]) {
                throw new FormatException("Tile [" + i / across + "," + i % across + "] has " + byteCounts[i]
                    + " bytes at offset " + offsets[i] + ", outside the " + file.length() + "-byte file");
            }
        }
    }

    private long[] longs(RandomAccessInputStream in, int tag) throws FormatException, IOException {
        Object value = ifd.getIFDValue(tag);
        long[] v;
        if (value instanceof OnDemandLongArray lazy) {
            lazy.setStream(in);
            v = lazy.toArray();
        } else {
            v = ifd.getIFDLongArray(tag);
        }
        if (v != null) {
            for (int i = 0; i < v.length; i++) {
                if (v[i] < 0) {
                    v[i] += 0x100000000L; // a 32-bit LONG read as a signed int
                }
            }
        }
        return v;
    }

    private static byte[] tablesPrefix(IFD ifd) throws FormatException {
        byte[] tables = switch (ifd.getIFDValue(IFD.JPEG_TABLES)) {
            case byte[] b -> b;
            case short[] s -> { // a BYTE-typed tag
                byte[] b = new byte[s.length];
                for (int i = 0; i < s.length; i++) {
                    b[i] = (byte) s[i];
                }
                yield b;
            }
            case null, default -> null;
        };
        if (tables == null || tables.length < 4) {
            return null; // absent, or too short to hold anything: the tiles must be self-contained
        }
        int n = tables.length;
        if ((tables[0] & 0xff) != 0xFF || (tables[1] & 0xff) != 0xD8
                || (tables[n - 2] & 0xff) != 0xFF || (tables[n - 1] & 0xff) != 0xD9) {
            throw new FormatException("The JPEGTables tag is not an SOI ... EOI tables stream");
        }
        if (!ifd.containsKey(IFD.PHOTOMETRIC_INTERPRETATION)) {
            throw new FormatException("JPEG tiles with shared tables but no Photometric tag; their colour space is unknown");
        }
        PhotoInterp photometric = ifd.getPhotometricInterpretation();
        byte[] app14 = switch (photometric) {
            case Y_CB_CR -> APP14_YCBCR;
            case RGB -> APP14_RGB;
            default -> throw new FormatException("Cannot copy JPEG tiles with photometric " + photometric);
        };
        byte[] prefix = new byte[n - 2 + app14.length];
        System.arraycopy(tables, 0, prefix, 0, n - 2);
        System.arraycopy(app14, 0, prefix, n - 2, app14.length);
        return prefix;
    }

    /** An Adobe APP14 marker segment with the given colour transform (0 = RGB, 1 = YCbCr). */
    private static byte[] adobe(int transform) {
        return new byte[] {(byte) 0xFF, (byte) 0xEE, 0, 14, 'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, (byte) transform};
    }

    /**
     * The tiles of whichever IFD was read last, and the open file. For a reader to own, and
     * close when it closes.
     */
    static final class Cache implements AutoCloseable {
        private TileFile file;
        private JpegTiffTiles tiles;

        byte[] read(String path, RandomAccessInputStream in, IFD ifd, int row, int col) throws FormatException, IOException {
            if (file == null) {
                file = new TileFile(path);
            }
            if (tiles == null || tiles.ifd != ifd) {
                tiles = new JpegTiffTiles(file, in, ifd);
            }
            return tiles.read(row, col);
        }

        @Override
        public void close() throws IOException {
            tiles = null;
            if (file != null) {
                try {
                    file.close();
                } finally {
                    file = null;
                }
            }
        }
    }
}
