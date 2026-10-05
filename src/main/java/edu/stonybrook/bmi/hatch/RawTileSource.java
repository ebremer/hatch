package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import loci.formats.FormatException;
import loci.formats.IFormatReader;

/** A reader that hands out the JPEG tiles of its current series as stored, without decoding them. */
public interface RawTileSource extends IFormatReader {

    /**
     * Checks that the tiles of the current series can be copied verbatim (JPEG-compressed,
     * 8-bit RGB, tiled) and returns their grid.
     *
     * @throws FormatException if they cannot
     */
    RawTileLayout getRawTileLayout() throws FormatException;

    /**
     * The tile at (row, col) of the first plane of the current series, as a standalone JPEG
     * stream.
     *
     * @return the JPEG, or null if the source does not store this tile
     */
    byte[] getRawTile(int row, int col) throws FormatException, IOException;
}
