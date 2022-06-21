package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
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
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.ome.OMEPyramidStore;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;
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
public class VSI2TIF {
    private CellSensReader reader;
    private String inputFile;
    private String outputFile;
    private int tileSizeX;
    private int tileSizeY;
    private int height;
    private int width;
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
    public static String software = "hatch 1.1.4 by Wing-n-Beak";
    
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
            int i=0;
            meta.setImageID("Image:"+i,i);
            meta.setPixelsID("Pixels:"+i,i);
            meta.setChannelID("Channel:"+i+":0", i, 0);
            meta.setChannelSamplesPerPixel(new PositiveInteger(3),i, 0);
            meta.setPixelsBigEndian(false, i);
            meta.setPixelsInterleaved(true, i);
            meta.setPixelsDimensionOrder(DimensionOrder.XYCZT, i);
            meta.setPixelsType(PixelType.UINT8, i);
            meta.setPixelsSizeX(new PositiveInteger(width),i);
            meta.setPixelsSizeY(new PositiveInteger(height),i);
            meta.setPixelsSizeZ(new PositiveInteger(1), i);
            meta.setPixelsSizeC(new PositiveInteger(3), i);
            meta.setPixelsSizeT(new PositiveInteger(1), i);
            meta.setPixelsPhysicalSizeX(ppx, i);
            meta.setPixelsPhysicalSizeY(ppy, i);
            meta.setPixelsPhysicalSizeZ(new Length(1, UNITS.MICROMETER), i);
            int w = width;
            int h = height;
            for (int j=1; j<depth; j++) {
                int cx = ((w%(2*tileSizeX))==1) ? 1 : 0;
                int cy = ((h%(2*tileSizeY))==1) ? 1 : 0;
                System.out.println((h%(2*tileSizeY)));
                System.out.println(w+" B "+h+"  cx/cy "+cx+" "+cy);
                w = (w / 2) - cx;
                h = (h / 2) - cy;
                System.out.println(w+" A "+h);
                if (verbose) {
                    if (cx==1) System.out.println("clip x");
                    if (cy==1) System.out.println("clip y");
                    System.out.println("Pyramid : "+w+" "+ h +" "+Math.ceil(w/512)+" "+Math.ceil(h/512));
                }
                ((OMEPyramidStore) meta).setResolutionSizeX(new PositiveInteger(w), 0, j);
                ((OMEPyramidStore) meta).setResolutionSizeY(new PositiveInteger(h), 0, j);
            }
            writer.setMetadataRetrieve(meta);
            writer.setBigTiff(true);
            writer.setId(outputFile);
            writer.setCompression("JPEG");
            writer.setWriteSequentially(true);
            writer.setInterleaved(true);
            writer.setTileSizeX(tileSizeX);
            writer.setTileSizeY(tileSizeY);
        } catch (DependencyException | ServiceException | FormatException | IOException ex) {
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
            width = reader.getSizeX();
            height = reader.getSizeY();
            MetadataRetrieve retrieve = (MetadataRetrieve) reader.getMetadataStore();
            ppx = retrieve.getPixelsPhysicalSizeX(maximage);
            ppy = retrieve.getPixelsPhysicalSizeY(maximage);
            SetPPS();
            if (verbose) {
                System.out.println("Image Size : "+width+"x"+height);
                System.out.println("Tile size  : "+tileSizeX+"x"+tileSizeY);
            }
            File dest = new File(outputFile);
            if (dest.exists()) {
                dest.delete();
            }
            int size = Math.min(width, height);
            int ss = (int) Math.ceil(Math.log(size)/Math.log(2));
            int tiless = (int) Math.ceil(Math.log(tileSizeX)/Math.log(2));
            depth = ss-tiless;
            if (verbose) {
                System.out.println("# of scales to be generated : "+depth);
            }
            SetupWriter();
        } catch (DependencyException | ServiceException | FormatException | IOException ex) {
            Logger.getLogger(VSI2TIF.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public int effSize(int tileX, int width) {
        return (tileX + tileSizeX) < width ? tileSizeX : width - tileX;
    }
    
    public void SetPPS() {
        Double physicalSizeX = ppx == null || ppx.value(UNITS.MICROMETER) == null ? null : ppx.value(UNITS.MICROMETER).doubleValue();
        if (physicalSizeX == null || physicalSizeX == 0) {
            physicalSizeX = 0d;
        } else {
            physicalSizeX = 1d / physicalSizeX;
        }
        Double physicalSizeY = ppy == null || ppy.value(UNITS.MICROMETER) == null ? null : ppy.value(UNITS.MICROMETER).doubleValue();
        if (physicalSizeY == null || physicalSizeY == 0) {
            physicalSizeY = 0d;
        } else {
            physicalSizeY = 1d / physicalSizeY;
        }
        px = new TiffRational((long) (physicalSizeX * 1000 * 10000), 1000);
        py = new TiffRational((long) (physicalSizeY * 1000 * 10000), 1000);
    }
    
    public void Dump2File(byte[] buffer, int a, int b) {
        try {
            File f = new File("/vsi/dump/0 === "+a+"-"+b+".jpg");
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(buffer);
                fos.flush();
            }
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Pyramid.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Pyramid.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public void DumpBI2File(BufferedImage bi, int a, int b) {
        try {
            File f = new File("/vsi/dump/0 === "+a+"-"+b+"X.jpg");
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            ImageIO.write(bi, "jpg", f);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Pyramid.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Pyramid.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
        
    public void readWriteTiles() throws FormatException, IOException {
        if (verbose) {
            System.out.println("transferring image data...");
        }
        reader.setSeries(maximage);
        System.out.println("START : "+width+" "+height+" "+tileSizeX+" "+tileSizeY);
        int nXTiles = width / tileSizeX;
        int nYTiles = height / tileSizeY;
        if (nXTiles * tileSizeX != width) nXTiles++;
        if (nYTiles * tileSizeY != height) nYTiles++;
        pyramid = new Pyramid(nXTiles,nYTiles,tileSizeX,tileSizeY);
        writer.setSeries(0);
        writer.setResolution(0);
        byte[] rawbuffer = new byte[reader.getTileSize()];
        System.out.println("SPAN : "+nXTiles+" "+nYTiles);
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
                byte[] raw = reader.getRaw(rawbuffer,0, y, x);
                IFD ifd = new IFD();
                ifd.put(IFD.RESOLUTION_UNIT, 3);
                ifd.put(IFD.X_RESOLUTION, px);
                ifd.put(IFD.Y_RESOLUTION, py);
                ifd.put(777, raw);
                //Dump2File(raw,x,y);
                writer.saveBytes(0, buf, ifd, tileX, tileY, effTileSizeX, effTileSizeY);
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raw));
                //DumpBI2File(bi,x,y);
                bi = bi.getSubimage(0, 0, effTileSizeX, effTileSizeY);
                pyramid.put(bi, x, y);
            }
        }
        if (verbose) {
            Lapse();
            System.out.println("Generate image pyramid...");
        }
        for (int s=1;s<depth;s++) {
            if (verbose) {
                System.out.println("Level : "+s+" of "+depth);
            }
            pyramid.Lump();
            pyramid.Shrink(0.5f);
            System.out.println(pyramid.gettilesX()+" X "+pyramid.gettilesY());
            writer.setSeries(0);
            writer.setResolution(s);
            if (verbose) {
                System.out.println("Writing level "+s+"...");
            }
            for (int y=0; y<pyramid.gettilesY(); y++) {
                for (int x=0; x<pyramid.gettilesX(); x++) {
                    byte[] b = pyramid.GetImageBytes(x, y);
                    IFD ifd = new IFD();
                    int tw = pyramid.getBufferedImage(x,y).getWidth();
                    int th = pyramid.getBufferedImage(x,y).getHeight();
                    ifd.put(IFD.RESOLUTION_UNIT, 3);
                    ifd.put(IFD.X_RESOLUTION, px);
                    ifd.put(IFD.Y_RESOLUTION, py);
                    ifd.put(777, b);
                    BufferedImage bi = pyramid.getBufferedImage(x, y);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(bi, "jpg", baos);
                    byte[] raw = baos.toByteArray();
                    //byte[] raw = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData();
                    writer.saveBytes(0, raw, ifd, x*tileSizeX, y*tileSizeY, tw, th);
                    pyramid.Dump2File(raw, x, y);
                }
            }
        }
    }
    
    public void SetVerbose(boolean x) {
        verbose = x;
    }
    
    public void DisplayHelp() {
        System.out.println(software);
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