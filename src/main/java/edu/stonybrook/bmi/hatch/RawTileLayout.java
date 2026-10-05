package edu.stonybrook.bmi.hatch;

/**
 * The grid of raw JPEG tiles a reader hands out for its current series via
 * {@link RawTileSource#getRawTile(int, int)}.
 *
 * @param width       width in pixels of the image the tile grid covers
 * @param height      height in pixels of the image the tile grid covers
 * @param tileWidth   tile width in pixels
 * @param tileHeight  tile height in pixels
 * @param tilesAcross number of tile columns
 * @param tilesDown   number of tile rows
 * @param background  0xRRGGBB fill for tiles the source does not store
 */
public record RawTileLayout(int width, int height, int tileWidth, int tileHeight,
                            int tilesAcross, int tilesDown, int background) {

    public static final int WHITE = 0xFFFFFF;
}
