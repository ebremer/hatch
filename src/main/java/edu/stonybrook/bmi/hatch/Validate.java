package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffParser;

/**
 * Checks that a converted pyramid is complete and readable.
 *
 * @author erich
 */
public class Validate {
    private static final Logger LOGGER = Logger.getLogger(Validate.class.getName());
    /** The smallest level must fit in this many pixels each way. */
    static final int MAX_SMALLEST_LEVEL = 1024;

    /**
     * @return true if {@link #check} finds nothing wrong; problems are logged
     */
    public static boolean file(Path path) {
        try {
            check(path);
            return true;
        } catch (IOException | FormatException | RuntimeException ex) {
            LOGGER.log(Level.SEVERE, "{0} ==> {1}", new Object[]{path, ex.getMessage()});
            return false;
        }
    }

    /**
     * Checks that the IFD chain stays inside the file and ends; that every level is a tiled
     * JPEG image whose tiles all lie inside the file; that the last tile of each level decodes
     * to a full tile whose JPEG structure matches the level's Photometric and YCbCrSubSampling
     * tags; that each level is half the one before (rounded up); and that the smallest level
     * fits in 1024x1024.
     *
     * @throws FormatException describing the first problem found
     */
    public static void check(Path path) throws IOException, FormatException {
        try (RandomAccessInputStream in = new RandomAccessInputStream(path.toString());
             TileFile file = new TileFile(path.toString())) {
            TiffParser parser = new TiffParser(in);
            if (parser.checkHeader() == null) {
                throw new FormatException("Not a TIFF file");
            }
            List<Long> chain = ifdChain(in, file.length());
            long previousWidth = 0;
            long previousHeight = 0;
            for (int level = 0; level < chain.size(); level++) {
                IFD ifd = parser.getIFD(chain.get(level));
                String where = "Level " + level + ": ";
                long width = ifd.getImageWidth();
                long height = ifd.getImageLength();
                if (width < 1 || height < 1) {
                    throw new FormatException(where + "image is " + width + "x" + height);
                }
                if (level > 0 && (width != (previousWidth + 1) / 2 || height != (previousHeight + 1) / 2)) {
                    throw new FormatException(where + width + "x" + height + " is not half of "
                        + previousWidth + "x" + previousHeight);
                }
                JpegTiffTiles.layout(ifd);
                JpegTiffTiles tiles = new JpegTiffTiles(file, in, ifd);
                tiles.checkAllStored();
                int rows = (int) ifd.getTilesPerColumn();
                int cols = (int) ifd.getTilesPerRow();
                byte[] last = tiles.read(rows - 1, cols - 1);
                checkTile(ifd, last, where);
                previousWidth = width;
                previousHeight = height;
            }
            if (previousWidth > MAX_SMALLEST_LEVEL || previousHeight > MAX_SMALLEST_LEVEL) {
                throw new FormatException("Smallest level (" + chain.size() + " levels) is " + previousWidth + "x"
                    + previousHeight + "; it must fit in " + MAX_SMALLEST_LEVEL + "x" + MAX_SMALLEST_LEVEL);
            }
        }
    }

    /** The JPEG structure must match the tags and decode to a whole tile. */
    private static void checkTile(IFD ifd, byte[] jpeg, String where) throws IOException, FormatException {
        JPEGTools.JpegInfo info = JPEGTools.inspect(jpeg);
        if (!ifd.containsKey(IFD.PHOTOMETRIC_INTERPRETATION)) {
            throw new FormatException(where + "no Photometric tag");
        }
        PhotoInterp photometric = ifd.getPhotometricInterpretation();
        boolean matches = switch (photometric) {
            case RGB -> info.components() == 3 && info.rgb();
            case Y_CB_CR -> {
                int[] sampling = ifd.containsKey(IFD.Y_CB_CR_SUB_SAMPLING)
                    ? ifd.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING) : new int[] {2, 2};
                yield info.components() == 3 && !info.rgb()
                    && sampling.length == 2 && sampling[0] == info.hSubsampling() && sampling[1] == info.vSubsampling();
            }
            default -> false;
        };
        if (!matches) {
            throw new FormatException(where + "tags say " + photometric
                + (ifd.containsKey(IFD.Y_CB_CR_SUB_SAMPLING) ? " " + Arrays.toString(ifd.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING)) : "")
                + " but the tiles are " + info);
        }
        BufferedImage image = JPEGTools.decode(jpeg);
        if (image.getWidth() != ifd.getTileWidth() || image.getHeight() != ifd.getTileLength()) {
            throw new FormatException(where + "last tile decodes to " + image.getWidth() + "x" + image.getHeight()
                + ", not " + ifd.getTileWidth() + "x" + ifd.getTileLength());
        }
    }

    /**
     * Offsets of the IFDs, following the chain from the header. Fails if an offset points
     * outside the file or back into the chain, rather than stopping there.
     */
    private static List<Long> ifdChain(RandomAccessInputStream in, long length) throws IOException, FormatException {
        in.seek(2);
        boolean bigTiff = in.readShort() == 43;
        long offset;
        if (bigTiff) {
            in.seek(8);
            offset = in.readLong();
        } else {
            in.seek(4);
            offset = in.readInt() & 0xFFFFFFFFL;
        }
        List<Long> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        while (offset != 0) {
            if (offset < (bigTiff ? 16 : 8) || offset + (bigTiff ? 8 : 2) > length) {
                throw new FormatException("IFD " + chain.size() + " offset " + offset + " is outside the "
                    + length + "-byte file; the file may be truncated");
            }
            if (!seen.add(offset)) {
                throw new FormatException("The IFD chain loops back to offset " + offset);
            }
            chain.add(offset);
            in.seek(offset);
            long entries = bigTiff ? in.readLong() : in.readUnsignedShort();
            long next = offset + (bigTiff ? 8 + entries * 20 : 2 + entries * 12);
            if (next + (bigTiff ? 8 : 4) > length) {
                throw new FormatException("IFD " + (chain.size() - 1) + " runs past the end of the file");
            }
            in.seek(next);
            offset = bigTiff ? in.readLong() : in.readInt() & 0xFFFFFFFFL;
        }
        if (chain.isEmpty()) {
            throw new FormatException("The file has no images");
        }
        return chain;
    }
}
