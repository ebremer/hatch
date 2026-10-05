package edu.stonybrook.bmi.hatch;

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
import java.util.logging.LogManager;

/**
 *
 * @author erich
 */
public class X2TIF implements AutoCloseable {
    private FormatReader reader;
    private final String inputFile;
    private final String dest;
    private int tileSizeX;
    private int tileSizeY;
    private int height;
    private int width;
    private int maximage;
    private Pyramid pyramid;
    private final StopWatch time;
    private int depth;
    private Length ppx;
    private Length ppy;
    private TiffRational px;
    private TiffRational py;
    private int TileSize;
    private final HatchParameters params;
    private HatchWriter writer;
    private IMetadata meta;
    private RawTileLayout layout;
    private static final Logger LOGGER;
    private XMP xmp = null;

    static {
         try {
             LogManager.getLogManager().readConfiguration(X2TIF.class.getResourceAsStream("/logging.properties"));
         } catch (IOException | SecurityException | ExceptionInInitializerError ex) {
             Logger.getLogger(X2TIF.class.getName()).log(Level.SEVERE, "Failed to read logging.properties file", ex);
         }
         LOGGER = Logger.getLogger(X2TIF.class.getName());
     }

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
                case ".tif" -> reader = new TiffReader();
                case ".svs" -> reader = new SVSReader();
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
            MetadataRetrieve retrieve = (MetadataRetrieve) reader.getMetadataStore();
            ppx = retrieve.getPixelsPhysicalSizeX(maximage);
            ppy = retrieve.getPixelsPhysicalSizeY(maximage);
            SetPPS();
            if (params.verbose) {
                LOGGER.log(Level.INFO, "Image Size   : {0}x{1}", new Object[]{width, height});
                LOGGER.log(Level.INFO, "Tile size    : {0}x{1}", new Object[]{tileSizeX, tileSizeY});
            }
            int size = Math.max(width, height);
            int ss = (int) Math.ceil(Math.log(size)/Math.log(2));
            int tiless = (int) Math.ceil(Math.log(tileSizeX)/Math.log(2));
            depth = ss-tiless+2;
            TileSize = tileSizeX * tileSizeY * 24;
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
            case SVSReader r -> {
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

    private int MaxImage(FormatReader reader) {
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

    public int effSize(int tileX, int width) {
        return (tileX + tileSizeX) < width ? tileSizeX : width - tileX;
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
    private JPEGTools.JpegInfo firstStoredTile(byte[] rawbuffer) throws FormatException, IOException {
        for (int y=0; y<layout.tilesDown(); y++) {
            for (int x=0; x<layout.tilesAcross(); x++) {
                byte[] raw = reader.getRawBytes(rawbuffer, 0, y, x);
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
        pyramid = new Pyramid(params,nXTiles,nYTiles,tileSizeX,tileSizeY,width,height);
        pyramid.setSource(inputFile);
        byte[] rawbuffer = new byte[TileSize+20];
        JPEGTools.JpegInfo jpeg = firstStoredTile(rawbuffer);
        if (jpeg.components() != 3) {
            throw new FormatException("Tiles have " + jpeg.components() + " colour component(s); only RGB JPEG tiles are supported");
        }
        loci.formats.tiff.IFD ifd = new loci.formats.tiff.IFD();
        ifd.put(IFD.TILE_WIDTH, tileSizeX);
        ifd.put(IFD.TILE_LENGTH, tileSizeY);
        ifd.put(IFD.IMAGE_WIDTH, (long) width);
        ifd.put(IFD.IMAGE_LENGTH, (long) height);
        ifd.put(IFD.TILE_OFFSETS, new long[numtiles]);
        ifd.put(IFD.TILE_BYTE_COUNTS, new long[numtiles]);
        if (xmp!=null) {
            ifd.putIFDValue(700, byte2short(xmp.getXMPString().getBytes(StandardCharsets.UTF_8)));
        }
        ifd.put(IFD.COMPRESSION, 7);
        ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 8, 8});
        ifd.put(IFD.SAMPLES_PER_PIXEL, 3);
        ifd.put(IFD.PLANAR_CONFIGURATION, 1);
        ifd.put(IFD.SOFTWARE, Hatch.software);
        ifd.putIFDValue(IFD.IMAGE_DESCRIPTION, "");
        ifd.put(IFD.ORIENTATION, 1);
        if (px != null && py != null) {
            ifd.put(IFD.X_RESOLUTION, px);
            ifd.put(IFD.Y_RESOLUTION, py);
            ifd.put(IFD.RESOLUTION_UNIT, 3);
        }
        ifd.put(IFD.SAMPLE_FORMAT, new int[] {1, 1, 1});
        // the base level carries the source's JPEG streams, so its tags must describe them
        if (jpeg.rgb()) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.RGB.getCode());
        } else {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            ifd.put(IFD.Y_CB_CR_SUB_SAMPLING, new int[] {jpeg.hSubsampling(), jpeg.vSubsampling()});
        }
        byte[] blank = null;
        int missing = 0;
        for (int y=0; y<nYTiles; y++) {
            if (params.verbose) {
                float perc = 100f*y/nYTiles;
                LOGGER.log(Level.INFO,String.format("%.2f%%",perc));
            }
            for (int x=0; x<nXTiles; x++) {
                byte[] raw = reader.getRawBytes(rawbuffer, 0, y, x);
                if (raw == null) {
                    // not stored in the source: stand in a background tile encoded like the real ones
                    if (blank == null) {
                        blank = JPEGTools.blankTile(tileSizeX, tileSizeY, layout.background(), jpeg, params.quality);
                    }
                    raw = blank;
                    missing++;
                } else if (!jpeg.equals(JPEGTools.inspect(raw))) {
                    throw new FormatException("Tile [" + y + "," + x + "] is encoded as " + JPEGTools.inspect(raw)
                        + " but the first tile as " + jpeg + "; one TIFF level cannot describe both");
                }
                // with no reduced levels to follow, the last base tile must terminate the IFD chain
                boolean last = (depth <= 1) && (x == nXTiles-1) && (y == nYTiles-1);
                writer.writeIFDStrips(ifd, raw, last, x*tileSizeX, y*tileSizeY);
                pyramid.put(raw, x, y);
            }
        }
        if (missing > 0) {
            LOGGER.log(Level.INFO, "{0} of {1} tiles are not stored in {2}; filled with background colour",
                new Object[]{missing, numtiles, inputFile});
        }
        if (params.verbose) {
            time.Cumulative();
            LOGGER.log(Level.INFO,"Generate image pyramid...");
        }
        ifd.remove(700);
        ifd.remove(IFD.Y_CB_CR_SUB_SAMPLING);
        ifd.remove(IFD.IMAGE_DESCRIPTION);
        ifd.remove(IFD.SOFTWARE);
        ifd.remove(IFD.X_RESOLUTION);
        ifd.remove(IFD.Y_RESOLUTION);
        ifd.put(IFD.COMPRESSION, 7);
        for (int s=1;s<depth;s++) {
            if (params.verbose) {
                LOGGER.log(Level.INFO, "Level : {0} of {1}", new Object[]{s, depth});
            }
            if (params.verbose) {
                LOGGER.log(Level.INFO,"Lump...");
            }
            pyramid.Lump();
            if (params.verbose) {
                LOGGER.log(Level.INFO,"Shrink...");
            }
            pyramid.Shrink();
            if (params.verbose) {
                LOGGER.log(Level.INFO, "{0} X {1}", new Object[]{pyramid.gettilesX(), pyramid.gettilesY()});
                LOGGER.log(Level.INFO, "Resolution S={0} {1}x{2}", new Object[]{s, pyramid.getWidth(), pyramid.getHeight()});
            }
            writer.nextImage();
            if (params.verbose) {
                LOGGER.log(Level.INFO, "Writing level {0}...", s);
            }
            numtiles = pyramid.gettilesX()*pyramid.gettilesY();
            ifd.put(IFD.NEW_SUBFILE_TYPE, 1L);
            ifd.put(IFD.IMAGE_WIDTH, (long) pyramid.getWidth());
            ifd.put(IFD.IMAGE_LENGTH, (long) pyramid.getHeight());
            ifd.put(IFD.TILE_OFFSETS, new long[numtiles]);
            ifd.put(IFD.TILE_BYTE_COUNTS, new long[numtiles]);
            // reduced levels are re-encoded by ImageIO as JFIF YCbCr 4:2:0 (the TIFF default subsampling)
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR.getCode());
            for (int y=0; y<pyramid.gettilesY(); y++) {
                for (int x=0; x<pyramid.gettilesX(); x++) {
                    byte[] b = pyramid.GetImageBytes(x, y);
                    writer.writeIFDStrips(ifd, b, ((x==(pyramid.gettilesX()-1))&&(y==(pyramid.gettilesY()-1))&&(s==depth-1)), x*tileSizeX, y*tileSizeY);
                }
            }
        }
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
            writer = new HatchWriter(part.toString());
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
