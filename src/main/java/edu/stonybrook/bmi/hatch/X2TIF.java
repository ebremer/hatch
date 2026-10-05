package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.ome.OMEPyramidStore;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffRational;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.PositiveInteger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/**
 *
 * @author erich
 */
public class X2TIF implements AutoCloseable {
    private RawTileSource reader;
    private final String inputFile;
    private final String dest;
    private int tileSizeX;
    private int tileSizeY;
    private int height;
    private int width;
    private int maximage;
    private final StopWatch time;
    private int depth;
    private Length ppx;
    private Length ppy;
    private TiffRational px;
    private TiffRational py;
    private final HatchParameters params;
    private TiledTiffWriter writer;
    private IMetadata meta;
    private RawTileLayout layout;
    private static final Logger LOGGER = Logger.getLogger(X2TIF.class.getName());
    private XMP xmp = null;

    public X2TIF(HatchParameters params, String src, String dest, Integer series) throws FormatException, IOException {
        time = new StopWatch();
        inputFile = src;
        this.dest = dest;
        this.params = params;
        if (params.verbose) {
            LOGGER.log(Level.INFO,"initializing...");
        }
        try {
            ServiceFactory factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata omexml = service.createOMEXMLMetadata();
            String end = inputFile.length() >= 4 ? inputFile.substring(inputFile.length()-4).toLowerCase() : "";
            switch (end) {
                case ".tif" -> reader = new HatchTiffReader();
                case ".svs" -> reader = new HatchSVSReader();
                case ".vsi" -> reader = new CellSensReader();
                default -> throw new IllegalArgumentException("Unsupported input file type (expected .tif/.svs/.vsi): " + inputFile);
            }
            reader.setMetadataStore(omexml);
            reader.setId(inputFile);
            if (series==null) {
                maximage = MaxImage(reader);
            } else {
                maximage = series;
            }
            if ((series!=null)&&((series<0)||(series>=reader.getSeriesCount()))) {
                throw new IllegalArgumentException("Series " + series + " does not exist in " + src);
            }
            reader.setSeries(maximage);
            // checks the tiles of this series can be copied verbatim, and gives their exact grid
            layout = reader.getRawTileLayout();
            if (reader.getPixelType() != FormatTools.UINT8 || reader.getRGBChannelCount() != 3) {
                throw new FormatException("Series " + maximage + " is " + FormatTools.getPixelTypeString(reader.getPixelType())
                    + " with " + reader.getRGBChannelCount() + " channel(s) per pixel; only 8-bit RGB images are supported");
            }
            if (reader.getImageCount() != 1) {
                throw new FormatException("Series " + maximage + " has " + reader.getImageCount()
                    + " planes (Z/C/T); only single-plane images are supported");
            }
            tileSizeX = layout.tileWidth();
            tileSizeY = layout.tileHeight();
            width = layout.width();
            height = layout.height();
            if (tileSizeX < 2 || tileSizeY < 2 || tileSizeX % 2 != 0 || tileSizeY % 2 != 0) {
                throw new FormatException("Tile size " + tileSizeX + "x" + tileSizeY
                    + " is not supported; reduced levels need even tile dimensions");
            }
            if (layout.tilesAcross() != PyramidBuilder.tilesFor(width, tileSizeX)
                    || layout.tilesDown() != PyramidBuilder.tilesFor(height, tileSizeY)) {
                throw new FormatException("A " + layout.tilesAcross() + "x" + layout.tilesDown() + " tile grid cannot cover "
                    + width + "x" + height + " pixels in " + tileSizeX + "x" + tileSizeY + " tiles");
            }
            MetadataRetrieve retrieve = (MetadataRetrieve) reader.getMetadataStore();
            ppx = retrieve.getPixelsPhysicalSizeX(maximage);
            ppy = retrieve.getPixelsPhysicalSizeY(maximage);
            SetPPS();
            if (params.verbose) {
                LOGGER.log(Level.INFO, "Image Size   : {0}x{1}", new Object[]{width, height});
                LOGGER.log(Level.INFO, "Tile size    : {0}x{1}", new Object[]{tileSizeX, tileSizeY});
            }
            depth = PyramidBuilder.levelCount(width, height, tileSizeX, tileSizeY);
            if (params.verbose) {
                LOGGER.log(Level.INFO, "# of scales to be generated : {0}", depth);
            }
            meta = service.createOMEXMLMetadata();
            meta.setImageID("Image:0", 0);
            meta.setPixelsID("Pixels:0", 0);
            meta.setChannelID("Channel:0", 0, 0);
            meta.setChannelSamplesPerPixel(new PositiveInteger(3), 0, 0);
            meta.setPixelsBigEndian(!reader.isLittleEndian(), 0);
            meta.setPixelsInterleaved(reader.isInterleaved(), 0);
            meta.setPixelsSizeX(new PositiveInteger(tileSizeX), 0);
            meta.setPixelsSizeY(new PositiveInteger(tileSizeY), 0);
            meta.setPixelsDimensionOrder(DimensionOrder.XYZCT, 0);
            meta.setPixelsType(PixelType.UINT8, 0);
            meta.setPixelsSizeX(new PositiveInteger(tileSizeX), 0);
            meta.setPixelsSizeY(new PositiveInteger(tileSizeY), 0);
            meta.setPixelsSizeZ(new PositiveInteger(1), 0);
            meta.setPixelsSizeC(new PositiveInteger(3), 0);
            meta.setPixelsSizeT(new PositiveInteger(1), 0);
            xmp = new XMP();
            FindMeta(xmp);
        } catch (DependencyException | ServiceException ex) {
            closeReaderAfterFailure(ex);
            throw new FormatException("Unable to create OME-XML metadata: " + ex.getMessage(), ex);
        } catch (FormatException | IOException | RuntimeException | Error ex) {
            // a constructor that throws is never close()d by try-with-resources
            closeReaderAfterFailure(ex);
            throw ex;
        }
    }

    private void closeReaderAfterFailure(Throwable failure) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ex) {
                failure.addSuppressed(ex);
            }
        }
    }

    /** Best-effort descriptive metadata: missing or malformed values are simply left out. */
    private void FindMeta(XMP xmp) {
        BigDecimal spacingX = mmPerPixel(ppx);
        BigDecimal spacingY = mmPerPixel(ppy);
        switch (reader) {
            case CellSensReader r -> {
                OMEPyramidStore mx = (OMEPyramidStore) reader.getMetadataStore();
                try {
                    String objectiveID = mx.getObjectiveSettingsID(maximage);
                    int instrument = -1;
                    int objective = -1;
                    int numberOfInstruments = mx.getInstrumentCount();
                    for (int ii = 0; ii < numberOfInstruments; ii++) {
                        int numObjectives = mx.getObjectiveCount(ii);
                        for (int oi = 0; oi < numObjectives; oi++) {
                            if (objectiveID.equals(mx.getObjectiveID(ii, oi))) {
                                instrument = ii;
                                objective = oi;
                                break;
                            }
                        }
                    }
                    BigDecimal t = BigDecimal.valueOf(mx.getPlaneExposureTime(maximage, 0).value(UNITS.MILLISECOND).doubleValue());
                    xmp.setExposureTime(t);
                    if (instrument >= 0 ) {
                        xmp.setMagnification(BigDecimal.valueOf(mx.getObjectiveNominalMagnification(instrument, objective)));
                        String manu = mx.getDetectorManufacturer(instrument, objective);
                        if (manu!=null) {
                            xmp.setManufacturer(manu);
                        }
                        String model = mx.getDetectorModel(instrument, objective);
                        if (model!=null) {
                            xmp.setManufacturerDeviceName(model);
                        }
                    }
                } catch (NullPointerException ex) {}
            }
            case HatchSVSReader r -> {
                Map<String,Object> list = r.getSeriesMetadata();
                BigDecimal magnification = number(list.get("AppMag"));
                if (magnification != null) {
                    xmp.setMagnification(magnification);
                }
                xmp.setManufacturer((String) list.get("Image Description"));
                xmp.setManufacturerDeviceName((String) list.get("ScanScope ID"));
                BigDecimal exposureTime = number(list.get("Exposure Time"));
                BigDecimal exposureScale = number(list.get("Exposure Scale"));
                if (exposureTime != null && exposureScale != null) {
                    xmp.setExposureTime(exposureTime.multiply(exposureScale).multiply(BigDecimal.valueOf(1000)));
                }
                BigDecimal mpp = number(list.get("MPP"));
                if (spacingX == null && mpp != null && mpp.signum() > 0) {
                    spacingX = mpp.movePointLeft(3); // microns -> mm
                    spacingY = spacingX;
                }
            }
            default -> {}
        }
        if (spacingX != null && spacingY != null) {
            xmp.setSizePerPixelXinMM(spacingX);
            xmp.setSizePerPixelYinMM(spacingY);
        }
    }

    private static BigDecimal mmPerPixel(Length size) {
        Number mm = size == null ? null : size.value(UNITS.MILLIMETER);
        if (mm == null || !(mm.doubleValue() > 0) || Double.isInfinite(mm.doubleValue())) {
            return null;
        }
        // drop unit-conversion noise (0.2505 um -> 2.5049999999999996E-4 mm)
        return new BigDecimal(mm.doubleValue()).round(new MathContext(12)).stripTrailingZeros();
    }

    private static BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int MaxImage(RawTileSource reader) {
        int ii = 0;
        int maxseries = 0;
        int maxx = Integer.MIN_VALUE;
        for (CoreMetadata x : reader.getCoreMetadataList()) {
            if (x.sizeX>maxx) {
                maxseries = ii;
                maxx = x.sizeX;
            }
            ii++;
        }
        if (params.verbose) LOGGER.log(Level.INFO, "Max image is series {0}", maxseries);
        return maxseries;
    }

    /** TIFF resolution in pixels per cm, or null when the source has no physical pixel size. */
    private void SetPPS() {
        px = pixelsPerCm(ppx);
        py = pixelsPerCm(ppy);
    }

    private static TiffRational pixelsPerCm(Length size) {
        Number um = size == null ? null : size.value(UNITS.MICROMETER);
        if (um == null || !(um.doubleValue() > 0) || Double.isInfinite(um.doubleValue())) {
            return null;
        }
        return new TiffRational((long) (10000d / um.doubleValue() * 1000), 1000);
    }

    public short[] byte2short(byte[] byteArray) {
        short[] shortArray = new short[byteArray.length];
        for (int i = 0; i < shortArray.length; i++) {
            shortArray[i] = (short) byteArray[i];
        }
        return shortArray;
    }

    /** JPEG structure of the first tile the source stores; every other tile must match it. */
    private JPEGTools.JpegInfo firstStoredTile() throws FormatException, IOException {
        for (int y=0; y<layout.tilesDown(); y++) {
            for (int x=0; x<layout.tilesAcross(); x++) {
                byte[] raw = reader.getRawTile(y, x);
                if (raw != null) {
                    return JPEGTools.inspect(raw);
                }
            }
        }
        throw new FormatException("Series " + maximage + " stores no tiles");
    }

    private void readWriteTiles() throws FormatException, IOException {
        if (params.verbose) {
            LOGGER.log(Level.INFO,"transferring image data...");
        }
        reader.setSeries(maximage);
        int nXTiles = layout.tilesAcross();
        int nYTiles = layout.tilesDown();
        int numtiles = nXTiles*nYTiles;
        JPEGTools.JpegInfo jpeg = firstStoredTile();
        if (jpeg.components() != 3) {
            throw new FormatException("Tiles have " + jpeg.components() + " colour component(s); only RGB JPEG tiles are supported");
        }
        TiledTiffWriter.Image[] levels = new TiledTiffWriter.Image[depth];
        levels[0] = writer.addImage(baseIFD(jpeg));
        int w = width;
        int h = height;
        for (int s=1; s<depth; s++) {
            w = PyramidBuilder.half(w);
            h = PyramidBuilder.half(h);
            if (params.verbose) {
                LOGGER.log(Level.INFO, "Level {0}: {1}x{2}", new Object[]{s, w, h});
            }
            levels[s] = writer.addImage(reducedIFD(w, h));
        }
        // the cores are shared between the -fp file processors
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(1, params.fp));
        try (PyramidBuilder pyramid = new PyramidBuilder(width, height, tileSizeX, tileSizeY, depth, params.quality, threads,
                (level, col, row, data) -> levels[level].writeTile(col, row, data))) {
            byte[] blank = null;
            BufferedImage blankImage = null;
            int missing = 0;
            for (int y=0; y<nYTiles; y++) {
                if (params.verbose) {
                    float perc = 100f*y/nYTiles;
                    LOGGER.log(Level.INFO,String.format("%.2f%%",perc));
                }
                for (int x=0; x<nXTiles; x++) {
                    byte[] raw = reader.getRawTile(y, x);
                    if (raw == null) {
                        // not stored in the source: stand in a background tile encoded like the real ones
                        if (blank == null) {
                            blank = JPEGTools.blankTile(tileSizeX, tileSizeY, layout.background(), jpeg, params.quality);
                            blankImage = JPEGTools.decode(blank);
                        }
                        levels[0].writeTile(x, y, blank);
                        pyramid.add(x, y, blankImage);
                        missing++;
                    } else {
                        if (!jpeg.equals(JPEGTools.inspect(raw))) {
                            throw new FormatException("Tile [" + y + "," + x + "] is encoded as " + JPEGTools.inspect(raw)
                                + " but the first tile as " + jpeg + "; one TIFF level cannot describe both");
                        }
                        levels[0].writeTile(x, y, raw);
                        pyramid.add(x, y, raw);
                    }
                }
            }
            pyramid.finish();
            if (missing > 0) {
                LOGGER.log(Level.INFO, "{0} of {1} tiles are not stored in {2}; filled with background colour",
                    new Object[]{missing, numtiles, inputFile});
            }
        }
        writer.finish();
    }

    /** Tags of the full-resolution level, which carries the source JPEG streams verbatim. */
    private IFD baseIFD(JPEGTools.JpegInfo jpeg) {
        IFD ifd = tiledIFD(width, height);
        if (xmp!=null) {
            ifd.putIFDValue(700, byte2short(xmp.getXMPString().getBytes(StandardCharsets.UTF_8)));
        }
        ifd.put(IFD.SOFTWARE, Hatch.software);
        ifd.putIFDValue(IFD.IMAGE_DESCRIPTION, "");
        if (px != null && py != null) {
            ifd.put(IFD.X_RESOLUTION, px);
            ifd.put(IFD.Y_RESOLUTION, py);
            ifd.put(IFD.RESOLUTION_UNIT, 3);
        }
        // the tags must describe the copied JPEG streams
        if (jpeg.rgb()) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.RGB.getCode());
        } else {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            ifd.put(IFD.Y_CB_CR_SUB_SAMPLING, new int[] {jpeg.hSubsampling(), jpeg.vSubsampling()});
        }
        return ifd;
    }

    /** Tags of a reduced level, whose tiles {@link JPEGTools#encode} writes as JFIF YCbCr 4:2:0. */
    private IFD reducedIFD(int w, int h) {
        IFD ifd = tiledIFD(w, h);
        ifd.put(IFD.NEW_SUBFILE_TYPE, 1L);
        ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
        ifd.put(IFD.Y_CB_CR_SUB_SAMPLING, new int[] {2, 2});
        return ifd;
    }

    private IFD tiledIFD(int w, int h) {
        IFD ifd = new IFD();
        ifd.put(IFD.IMAGE_WIDTH, (long) w);
        ifd.put(IFD.IMAGE_LENGTH, (long) h);
        ifd.put(IFD.TILE_WIDTH, tileSizeX);
        ifd.put(IFD.TILE_LENGTH, tileSizeY);
        ifd.put(IFD.COMPRESSION, 7);
        ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 8, 8});
        ifd.put(IFD.SAMPLES_PER_PIXEL, 3);
        ifd.put(IFD.PLANAR_CONFIGURATION, 1);
        ifd.put(IFD.ORIENTATION, 1);
        ifd.put(IFD.SAMPLE_FORMAT, new int[] {1, 1, 1});
        return ifd;
    }

    /**
     * Writes the pyramid to a sibling ".part" file and moves it over the destination only once
     * it is complete, so a failure never leaves a partial file and never destroys an existing one.
     */
    public void Execute() throws FormatException, IOException {
        Path target = Path.of(dest).toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(part);
        try {
            writer = new TiledTiffWriter(part.toString());
            readWriteTiles();
            writer.close();
            writer = null;
            moveIntoPlace(part, target);
        } catch (FormatException | IOException | RuntimeException | Error ex) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    ex.addSuppressed(e);
                }
                writer = null;
            }
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
        if (params.verbose) {
            time.Cumulative();
        }
    }

    private static void moveIntoPlace(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            reader.close();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
