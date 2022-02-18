package edu.stonybrook.bmi.hatch;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.OnDemandLongArray;

/**
 *
 * @author erich
 */
public class NeoTiffParser extends TiffParser {
    private boolean equalStrips = false;
    
    public NeoTiffParser(String filename) throws IOException {
        super(filename);
    }
    
    public byte[] getRawTile(IFD ifd, int row, int col) throws FormatException, IOException {
        byte[] jpegTable = (byte[]) ifd.getIFDValue(IFD.JPEG_TABLES);
        long tileWidth = ifd.getTileWidth();
        long tileLength = ifd.getTileLength();
        System.out.println(ifd.getTilesPerRow()+" "+ifd.getTilesPerColumn());
        int samplesPerPixel = ifd.getSamplesPerPixel();
        int planarConfig = ifd.getPlanarConfiguration();
        loci.formats.tiff.TiffCompression compression = ifd.getCompression();
        long numTileCols = ifd.getTilesPerRow();
        int pixel = ifd.getBytesPerSample()[0];
        int effectiveChannels = planarConfig == 2 ? 1 : samplesPerPixel;

    if (ifd.get(IFD.STRIP_BYTE_COUNTS) instanceof OnDemandLongArray) {
      OnDemandLongArray counts = (OnDemandLongArray) ifd.get(IFD.STRIP_BYTE_COUNTS);
      if (counts != null) {
        counts.setStream(in);
      }
    }
    if (ifd.get(IFD.TILE_BYTE_COUNTS) instanceof OnDemandLongArray) {
      OnDemandLongArray counts = (OnDemandLongArray) ifd.get(IFD.TILE_BYTE_COUNTS);
      if (counts != null) {
        counts.setStream(in);
      }
    }

    long[] stripByteCounts = ifd.getStripByteCounts();
    long[] rowsPerStrip = ifd.getRowsPerStrip();

    int offsetIndex = (int) (row * numTileCols + col);
    int countIndex = offsetIndex;
    if (equalStrips) {
      countIndex = 0;
    }
    if (stripByteCounts[countIndex] == (rowsPerStrip[0] * tileWidth) && pixel > 1) {
      stripByteCounts[countIndex] *= pixel;
    } else if (stripByteCounts[countIndex] < 0 && countIndex > 0) {

    }
    long stripOffset = 0;
    long nStrips = 0;
    if (ifd.getOnDemandStripOffsets() != null) {
      OnDemandLongArray stripOffsets = ifd.getOnDemandStripOffsets();
      stripOffsets.setStream(in);
      stripOffset = stripOffsets.get(offsetIndex);
      nStrips = stripOffsets.size();
    }
    else {
      long[] stripOffsets = ifd.getStripOffsets();
      stripOffset = stripOffsets[offsetIndex];
      nStrips = stripOffsets.length;
    }
    int size = (int) (tileWidth * tileLength * pixel * effectiveChannels);
    byte[] buf = new byte[size];
    if (stripByteCounts[countIndex] == 0 || stripOffset >= in.length()) {
      // make sure that the buffer is cleared before returning
      // the caller may be reusing the same buffer for multiple calls to getTile
      Arrays.fill(buf, (byte) 0);
      return buf;
    }
    int tileSize = (int) stripByteCounts[countIndex];
    if (jpegTable != null) {
      tileSize += jpegTable.length - 2;
    }
    byte[] tile = new byte[tileSize];
    if (jpegTable != null) {
      System.arraycopy(jpegTable, 0, tile, 0, jpegTable.length - 2);
      in.seek(stripOffset + 2);
      in.read(tile, jpegTable.length - 2, tile.length - (jpegTable.length - 2));
    }
    else {
      in.seek(stripOffset);
      in.read(tile);
    }
    if (ifd.getIFDIntValue(IFD.FILL_ORDER) == 2 && compression.getCode() <= loci.formats.tiff.TiffCompression.GROUP_4_FAX.getCode()) {
      for (int i=0; i<tile.length; i++) {
        tile[i] = (byte) (Integer.reverse(tile[i]) >> 24);
      }
    }
    return tile;
  }
       
    public static void main(String[] args) throws IOException, FormatException {
        NeoTiffParser tp = new NeoTiffParser("/HalcyonStorage/TCGA-3C-AALI-01Z-00-DX1.F6E9A5DF-D8FB-45CF-B4BD-C6B76294C291.svs");
        int row = 150, col = 150;
        IFDList ifdl = tp.getMainIFDs();
        System.out.println(ifdl.size());
        IFD ifd = ifdl.get(0);
        System.out.println(ifd.getTileWidth());
        System.out.println(ifd.isLittleEndian());
        System.out.println(ifd.getTilesPerRow());
        System.out.println(ifd.getTilesPerColumn());
        byte[] tile = tp.getRawTile(ifd, row, col);
        FileOutputStream fos = new FileOutputStream("/HalcyonStorage/wow2.jp2");
        fos.write(tile);
        fos.flush();
        fos.close();
        System.out.println(tile.length);
    }
    
}
