package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import loci.formats.FormatException;
import loci.formats.in.SVSReader;
import loci.formats.tiff.IFD;

/** Bio-Formats' Aperio SVS reader, plus verbatim access to the JPEG tiles of the current series. */
public class HatchSVSReader extends SVSReader implements RawTileSource {
    private final JpegTiffTiles.Cache rawTiles = new JpegTiffTiles.Cache();

    @Override
    public RawTileLayout getRawTileLayout() throws FormatException {
        return JpegTiffTiles.layout(getIFD(0));
    }

    @Override
    public byte[] getRawTile(int row, int col) throws FormatException, IOException {
        IFD ifd = getIFD(0); // also initializes the parser if needed
        return rawTiles.read(getCurrentFile(), tiffParser.getStream(), ifd, row, col);
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
