package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import loci.formats.tiff.IFD;

/** Bio-Formats' TIFF reader, plus verbatim access to the JPEG tiles of the current series. */
public class HatchTiffReader extends TiffReader implements RawTileSource {
    private final JpegTiffTiles.Cache rawTiles = new JpegTiffTiles.Cache();

    /** The IFD that stores the first plane of the current series. */
    private IFD rawIFD() throws FormatException {
        if (seriesToIFD) {
            return ifds.get(getSeries());
        }
        if (getSeriesCount() > 1) {
            throw new FormatException("Cannot tell which IFD stores series " + getSeries());
        }
        return ifds.get(0);
    }

    @Override
    public RawTileLayout getRawTileLayout() throws FormatException {
        return JpegTiffTiles.layout(rawIFD());
    }

    @Override
    public byte[] getRawTile(int row, int col) throws FormatException, IOException {
        if (tiffParser == null) {
            initTiffParser();
        }
        return rawTiles.read(getCurrentFile(), tiffParser.getStream(), rawIFD(), row, col);
    }

    @Override
    protected void initFile(String id) throws FormatException, IOException {
        try {
            super.initFile(id);
        } catch (FormatException | IOException | RuntimeException e) {
            // setId does not close a reader whose initialization failed; the file would stay open
            try {
                close();
            } catch (IOException c) {
                e.addSuppressed(c);
            }
            throw e;
        }
    }

    @Override
    public void close(boolean fileOnly) throws IOException {
        try {
            super.close(fileOnly);
        } finally {
            rawTiles.close();
        }
    }
}
