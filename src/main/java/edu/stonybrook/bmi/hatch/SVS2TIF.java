package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.gui.AWTImageTools;
import loci.formats.in.SVSReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.ome.OMEPyramidStore;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffRational;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.PositiveInteger;

/**
 *
 * @author erich
 */
public class SVS2TIF {
    private SVSReader reader;
    private String inputFile;
    private String outputFile;
    private int tileSizeX;
    private int tileSizeY;
    private int maximage;
    private Pyramid pyramid;
    private OMETiffWriter writer;
    private boolean verbose = false;
    private final long start;
    private int depth;
    private Length ppx;
    private Length ppy;
    private TiffRational px;
    private TiffRational py;
    
    public SVS2TIF() {
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
                meta.setPixelsPhysicalSizeX(ppx, i);
                meta.setPixelsPhysicalSizeY(ppy, i);
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
            writer.setWriteSequentially(true);
            writer.setInterleaved(true);
            writer.setTileSizeX(tileSizeX);
            writer.setTileSizeY(tileSizeY);
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
            reader = new SVSReader();
            reader.setMetadataStore(omexml);
            reader.setId(inputFile);
            maximage = MaxImage(reader);
            reader.setSeries(maximage);
            tileSizeX = reader.getOptimalTileWidth();
            tileSizeY = reader.getOptimalTileHeight();
            MetadataRetrieve retrieve = (MetadataRetrieve) reader.getMetadataStore();
            ppx = retrieve.getPixelsPhysicalSizeX(maximage);
            ppy = retrieve.getPixelsPhysicalSizeY(maximage);
            SetPPS();
            if (verbose) {
                System.out.println("Image Size : "+reader.getSizeX()+"x"+reader.getSizeY());
                System.out.println("Tile size  : "+tileSizeX+"x"+tileSizeY);
            }
            File dest = new File(outputFile);
            if (dest.exists()) {
                dest.delete();
            }
            int ss = (int) Math.ceil(Math.log(reader.getSizeX())/Math.log(2));
            int tiless = (int) Math.ceil(Math.log(tileSizeX)/Math.log(2));
            depth = ss-tiless;
            if (verbose) {
                System.out.println("# of scales to be generated : "+depth);
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
    
    public void SetPPS() {
        Double physicalSizeX = ppx == null || ppx.value(UNITS.MICROMETER) == null ? null : ppx.value(UNITS.MICROMETER).doubleValue();
        if (physicalSizeX == null || physicalSizeX.doubleValue() == 0) {
            physicalSizeX = 0d;
        } else {
            physicalSizeX = 1d / physicalSizeX;
        }
        Double physicalSizeY = ppy == null || ppy.value(UNITS.MICROMETER) == null ? null : ppy.value(UNITS.MICROMETER).doubleValue();
        if (physicalSizeY == null || physicalSizeY.doubleValue() == 0) {
            physicalSizeY = 0d;
        } else {
            physicalSizeY = 1d / physicalSizeY;
        }
        px = new TiffRational((long) (physicalSizeX * 1000 * 10000), 1000);
        py = new TiffRational((long) (physicalSizeY * 1000 * 10000), 1000);
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
        //reader.close();
        NeoTiffParser tp = new NeoTiffParser(inputFile);
        IFDList ifdl = tp.getMainIFDs();
        System.out.println(ifdl.size());
        IFD ifd = ifdl.get(0);
        System.out.println("COMPRESSION : "+ifd.getCompression());
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
                System.out.println(x+","+y+" "+effTileSizeX+"x"+effTileSizeY);
                byte[] buf = reader.openBytes(0, tileX, tileY, effTileSizeX, effTileSizeY);
                byte[] raw = tp.getRawTile(ifd, y, x);
                //FileOutputStream fos = new FileOutputStream("/HalcyonStorage/dump/"+x+"-"+y+".jp2");
                //fos.write(raw);
                //fos.flush();
                //fos.close();
                IFD ifdx = new IFD();
                ifdx.put(IFD.RESOLUTION_UNIT, 3);
                ifdx.put(IFD.X_RESOLUTION, px);
                ifdx.put(IFD.Y_RESOLUTION, py);
                ifd.put(777, raw);
                writer.saveBytes(0, buf, ifd, tileX, tileY, effTileSizeX, effTileSizeY);
                BufferedImage bi = AWTImageTools.makeImage(buf, effTileSizeX, effTileSizeY, false);
                //BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raw));
                bi = bi.getSubimage(0, 0, effTileSizeX, effTileSizeY);
                pyramid.put(bi, x, y, 0.5f);
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
                    IFD ifdx = new IFD();
                    int tw = pyramid.getBufferedImage(x,y).getWidth();
                    int th = pyramid.getBufferedImage(x,y).getHeight();
                    ifdx.put(IFD.RESOLUTION_UNIT, 3);
                    ifdx.put(IFD.X_RESOLUTION, px);
                    ifdx.put(IFD.Y_RESOLUTION, py);
                    if (ifdx.containsKey(IFD.COMPRESSION)) {
                        System.out.println(ifdx.getCompression());
                        ifdx.remove(IFD.COMPRESSION);
                    }
                    ifdx.put(IFD.COMPRESSION, TiffCompression.JPEG.getCode());
                    ifdx.put(777, b);
                    BufferedImage bi = pyramid.getBufferedImage(x, y);
                    byte[] raw = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData();
                    writer.saveBytes(0, raw, ifdx, x*tileSizeX, y*tileSizeY, tw, th);
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
        System.out.println("hatch - version 1.1.1");
        System.out.println("usage: hatch <src> <dest>");
        System.out.println("-v : verbose");
    }
    
    public void Execute(String src, String dest) {
        if (verbose) {
            System.out.println("initializing...");
        }
        SetSrcDest(src,dest);
        initialize();
        try {
            readWriteTiles();
            Lapse();
        } catch(IOException | FormatException e) {
            System.err.println(e.toString());
        }
    }
    
    public static void main(String[] args) {
        loci.common.DebugTools.setRootLevel("WARN");
        SVS2TIF v2t = new SVS2TIF();
        String[] tmp = {"/HalcyonStorage/TCGA-3C-AALI-01Z-00-DX1.F6E9A5DF-D8FB-45CF-B4BD-C6B76294C291.svs","/HalcyonStorage/TCGA-3C-AALI-01Z-00-DX1.F6E9A5DF-D8FB-45CF-B4BD-C6B76294C291.tif"};
        args = tmp;
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