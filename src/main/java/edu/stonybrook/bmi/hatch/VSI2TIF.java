package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.meta.IMetadata;
import loci.formats.ome.OMEPyramidStore;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.PositiveInteger;

/**
 *
 * @author erich
 */
public class VSI2TIF {
    private CellSensReader reader;
    private String inputFile;
    private String outputFile;
    private int tileSizeX;
    private int tileSizeY;
    private int maximage;
    private Pyramid pyramid;
    private OMETiffWriter writer;
    private boolean verbose = false;
    private long start;
    private int depth = 6;
    
    public VSI2TIF() {
        start = System.nanoTime();
    }
    
    public void SetSrcDest(String src, String dest) {
        inputFile = src;
        outputFile = dest;
    }
    
    public void Lapse() {
        long now = (System.nanoTime()-start)/1000000000L;
        System.out.println(now+" seconds");
    }
    
    public int MaxImage(IFormatReader reader) {
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
        return maxseries;
    }
    
    private void SetupWriter() {
        writer = new OMETiffWriter();
        ServiceFactory factory;
        try {
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata meta = service.createOMEXMLMetadata();
            for (int i=0; i<1; i++) {
                meta.setImageID("Image:"+i,i);
                meta.setPixelsID("Pixels:"+i,i);
                meta.setChannelID("Channel:"+i+":0", i, 0);
                meta.setChannelSamplesPerPixel(new PositiveInteger(3),i, 0);
                meta.setPixelsBigEndian(false, i);
                meta.setPixelsInterleaved(true, i);
                meta.setPixelsDimensionOrder(DimensionOrder.XYCZT, i);
                meta.setPixelsType(PixelType.UINT8, i);
                meta.setPixelsSizeX(new PositiveInteger(reader.getSizeX()>>i),i);
                meta.setPixelsSizeY(new PositiveInteger(reader.getSizeY()>>i),i);
                meta.setPixelsSizeZ(new PositiveInteger(1), i);
                meta.setPixelsSizeC(new PositiveInteger(3), i);
                meta.setPixelsSizeT(new PositiveInteger(1), i);
                meta.setPixelsPhysicalSizeX(new Length(0.3470547596724842, UNITS.MICROMETER), i);
                meta.setPixelsPhysicalSizeY(new Length(0.3470491808827766, UNITS.MICROMETER), i);
                meta.setPixelsPhysicalSizeZ(new Length(1, UNITS.MICROMETER), i);
            }
            for (int i=1; i<depth; i++) {
                int scale = (int) Math.pow(2, i);
                ((OMEPyramidStore) meta).setResolutionSizeX(new PositiveInteger(reader.getSizeX() / scale), 0, i);
                ((OMEPyramidStore) meta).setResolutionSizeY(new PositiveInteger(reader.getSizeY() / scale), 0, i);
            }
            writer.setMetadataRetrieve(meta);
            writer.setBigTiff(true);
            writer.setId(outputFile);
            writer.setCompression("JPEG");
            writer.setWriteSequentially(false);
            writer.setInterleaved(true);
            writer.setTileSizeX(reader.getOptimalTileWidth());
            writer.setTileSizeY(reader.getOptimalTileHeight());        
        } catch (DependencyException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ServiceException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        } catch (FormatException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private void initialize() {
        ServiceFactory factory;
        try {
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata omexml = service.createOMEXMLMetadata();
            reader = new CellSensReader();
            reader.setMetadataStore(omexml);
            reader.setId(inputFile);
            maximage = MaxImage(reader);
            reader.setSeries(maximage);
            tileSizeX = reader.getOptimalTileWidth();
            tileSizeY = reader.getOptimalTileHeight();
            if (verbose) {
                System.out.println("Image Size : "+reader.getSizeX()+"x"+reader.getSizeY());
                System.out.println("Tile size  : "+tileSizeX+"x"+tileSizeY);
            }
            File dest = new File(outputFile);
            if (dest.exists()) {
                dest.delete();
            }
            SetupWriter();
        } catch (DependencyException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ServiceException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        } catch (FormatException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
    
    public int effSize(int tileX, int width) {
        return (tileX + tileSizeX) < width ? tileSizeX : width - tileX;
    }
        
    public void readWriteTiles() throws FormatException, IOException {
        if (verbose) {
            System.out.println("transferring image data...");
        }
        reader.setSeries(maximage);
        int width = reader.getSizeX();
        int height = reader.getSizeY();
        int nXTiles = width / tileSizeX;
        int nYTiles = height / tileSizeY;
        if (nXTiles * tileSizeX != width) nXTiles++;
        if (nYTiles * tileSizeY != height) nYTiles++;
        pyramid = new Pyramid(nXTiles,nYTiles,reader.getOptimalTileWidth(),reader.getOptimalTileHeight());
        writer.setSeries(0);
        writer.setResolution(0);
        for (int y=0; y<nYTiles; y++) {
            if (verbose) {
                float perc = 100f*y/nYTiles;
                System.out.println(perc+"%");
            }
            for (int x=0; x<nXTiles; x++) {
                int tileX = x * tileSizeX;
                int tileY = y * tileSizeY;
                int effTileSizeX = (tileX + tileSizeX) < width ? tileSizeX : width - tileX;
                int effTileSizeY = (tileY + tileSizeY) < height ? tileSizeY : height - tileY;
                byte[] buf = reader.openBytes(0, tileX, tileY, effTileSizeX, effTileSizeY);
                byte[] raw = reader.getRaw(0, y, x);
                InputStream is = new ByteArrayInputStream(raw);
                BufferedImage bi = ImageIO.read(is);
                bi = bi.getSubimage(0, 0, effTileSizeX, effTileSizeY);
                pyramid.put(bi, x, y, 0.5f);
                IFD ifd = new IFD();
                ifd.put(777, raw);
                writer.saveBytes(0, buf, ifd, tileX, tileY, effTileSizeX, effTileSizeY);
            }
        }
        if (verbose) {
            Lapse();
            System.out.println("Generate image pyramid...");
        }
        pyramid.Lump();
        for (int s=1;s<depth;s++) {
            if (verbose) {
                System.out.println("Level : "+s+" of "+depth);
            }
            writer.setSeries(0);
            writer.setResolution(s);
            for (int y=0; y<pyramid.gettilesY(); y++) {
                for (int x=0; x<pyramid.gettilesX(); x++) {
                    byte[] b = pyramid.GetImageBytes(x, y);
                    IFD ifd = new IFD();
                    int tw = pyramid.getBufferedImage(x,y).getWidth();
                    int th = pyramid.getBufferedImage(x,y).getHeight();
                    ifd.putIFDValue(IFD.NEW_SUBFILE_TYPE, 1L);
                    ifd.putIFDValue(IFD.RESOLUTION_UNIT, 3);
                    ifd.putIFDValue(IFD.X_RESOLUTION, tileSizeX);
                    ifd.putIFDValue(IFD.Y_RESOLUTION, tileSizeY);
                    ifd.put(777, b);
                    BufferedImage bi = pyramid.getBufferedImage(x, y);
                    byte[] raw = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData();
                    writer.saveBytes(0, raw, ifd, x*tileSizeX, y*tileSizeY, tw, th);
                }
            }
            pyramid.Shrink(0.5f);
            pyramid.Lump();
        }
    }
    
    public void SetVerbose(boolean x) {
        verbose = x;
    }
    
    public void DisplayHelp() {
        System.out.println("hatch - version 1.0.0");
        System.out.println("usage: hatch <src> <dest>");
        System.out.println("-v : verbose");
    }
    
    public void Execute(String src, String dest) {
        if (verbose) {
            System.out.println("initializing...");
        }
        SetSrcDest(src,dest);
        initialize();
        if (verbose) {
            Lapse();
        }
        try {
            readWriteTiles();
            Lapse();
        } catch(IOException | FormatException e) {
            System.err.println(e.toString());
        }
    }
    
    public static void main(String[] args) {
        loci.common.DebugTools.setRootLevel("WARN");
        VSI2TIF v2t = new VSI2TIF();
        if ((args.length<2)||args.length>3) {
            v2t.DisplayHelp();
        } else {
            if (args.length==3) {
                if (args[0].equals("-v")) {
                    v2t.SetVerbose(true);
                    v2t.Execute(args[1],args[2]);
                } else {
                    v2t.DisplayHelp();
                }
            } else {
                v2t.Execute(args[0],args[1]);
            }
        }
    }
}