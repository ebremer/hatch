package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 *
 * @author erich
 */
public class Pyramid {
    private JPEGBuffer[][] tiles;
    private int tilesX;
    private int tilesY;
    private final int tileSizeX;
    private final int tileSizeY;
    private int xscale = 0;
    public static float DownScale = 0.5f;
    private int width;
    private int height;
    private final HatchParameters params;
    
    public Pyramid(HatchParameters params, int tilesX, int tilesY, int tileSizeX, int tileSizeY, int width, int height) {
        this.params = params;
        this.tilesX = tilesX;
        this.tilesY = tilesY;
        this.tileSizeX = tileSizeX;
        this.tileSizeY = tileSizeY;
        this.height = height;
        this.width = width;
        tiles = new JPEGBuffer[tilesX][tilesY];
    }
    
    public HatchParameters getParameters() {
        return params;
    }
    
    public int gettilesX() {
        return tilesX;
    }
    
    public int gettilesY() {
        return tilesY;
    }
    
    public int gettileSizeX() {
        return tileSizeX;
    }
    
    public int gettileSizeY() {
        return tileSizeY;
    }
    
    public JPEGBuffer[][] getTiles() {
        return tiles;
    }

    public void put(byte[] raw, int x, int y, int effTileSizeX, int effTileSizeY, float scale) throws IOException {        
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raw));
        bi = bi.getSubimage(0, 0, effTileSizeX, effTileSizeY);
        put(bi, x, y);
    }
    
    private void CalculateHeightandWidth() {
        width = (int) Math.round(width*DownScale);
        height = (int) Math.round(height*DownScale);
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
        
    public byte[] GetImageBytes(int a, int b) {
        ImageWriter jpgWriter = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = jpgWriter.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        param.setCompressionQuality(params.quality);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream outputStream = new MemoryCacheImageOutputStream(baos);
        outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        jpgWriter.setOutput(outputStream);
        if (tiles[a][b]!=null) {
            IIOImage outputImage = new IIOImage(tiles[a][b].GetBufferImage(), null, null);
            try {
                jpgWriter.write(null, outputImage, param);
            } catch (IOException ex) {
                Logger.getLogger(NeoJPEGCodec.class.getName()).log(Level.SEVERE, null, ex);
            }
            jpgWriter.dispose();
        } else {
            System.out.println("NULL TILE : "+a+"-"+b+" "+(tiles[a][b]==null));
        }
        return baos.toByteArray();
    }
    
    public BufferedImage getBufferedImage(int a, int b) {
        return tiles[a][b].GetBufferImage();
    }
    
    public void Dump2File(byte[] buffer, int a, int b) {
        try {
            File f = new File("/vsi/dump/"+xscale+" === "+a+"-"+b+".jpg");
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
    
    public void Dump() throws FileNotFoundException, IOException {
        for (int a=0; a<tilesX; a++) {
            for (int b=0; b<tilesY; b++) {
                ImageWriter jpgWriter = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
                ImageWriteParam param = jpgWriter.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
                param.setCompressionQuality(params.quality);
                FileOutputStream fos = new FileOutputStream("/vsi/whoa/"+xscale+"----"+a+"-"+b+".jpg");
                ImageOutputStream stream = ImageIO.createImageOutputStream(fos);
                jpgWriter.setOutput(stream);
                if (tiles[a][b]!=null) {
                    IIOImage outputImage = new IIOImage(tiles[a][b].GetBufferImage(), null, null);
                    try {
                        jpgWriter.write(null, outputImage, param);
                    } catch (IOException ex) {
                        Logger.getLogger(NeoJPEGCodec.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    jpgWriter.dispose();
                } else {
                    System.out.println("NULL TILE : "+a+"-"+b+" "+(tiles[a][b]==null));
                }
            }
        }
    }
    
    public void put(BufferedImage bi, int x, int y) {
        tiles[x][y] = new JPEGBuffer(bi,params.quality);
    }
    
    public void put(byte[] buffer, int x, int y) {
        tiles[x][y] = new JPEGBuffer(buffer);
    }
    
    public void put(BufferedImage bi, int x, int y, float scale) {
        AffineTransform at = new AffineTransform();
        at.scale(DownScale,DownScale);
        AffineTransformOp scaleOp =  new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        BufferedImage target = new BufferedImage((int)(DownScale*bi.getWidth()),(int)(DownScale*bi.getHeight()),bi.getType());
        scaleOp.filter(bi, target);
        put(target,x,y);
    }

    public void Shrink() {
        try (ExecutorService engine = Executors.newVirtualThreadPerTaskExecutor()) {
            JPEGBuffer c = tiles[tilesX-1][tilesY-1];
            if (c.GetBufferImage().getWidth()==1) {
                System.out.println("shrink clip x");
                tilesX--;
            }
            if (c.GetBufferImage().getHeight()==1) {
                System.out.println("shrink clip y");
                tilesY--;
            }
            for (int a=0; a<tilesX; a++) {
                for (int b=0; b<tilesY; b++) {
                    engine.submit(new SmushProcessor(this, a, b));
                }
            }
        }
        CalculateHeightandWidth();
    }
    
    public void Lump() {
        int neotilesX;
        int neotilesY;
        JPEGBuffer[][] neotiles;
        try (ExecutorService engine = Executors.newVirtualThreadPerTaskExecutor()) {
            xscale++;
            neotilesX = (int) Math.ceil(tilesX/2f);
            neotilesY = (int) Math.ceil(tilesY/2f);
            neotiles = new JPEGBuffer[neotilesX][neotilesY];
            for (int a=0; a<tilesX; a=a+2) {
                for (int b=0; b<tilesY; b=b+2) {
                    engine.submit(new MergeProcessor(this, neotiles, a, b));
                }
            }
        }
        tiles = neotiles;
        tilesX = neotilesX;
        tilesY = neotilesY;
    }
}

class SmushProcessor implements Runnable {
    private final Pyramid pyramid;
    private final int a;
    private final int b;
    
    public SmushProcessor(Pyramid pyramid, int a, int b) {
        this.pyramid = pyramid;
        this.a = a;
        this.b = b;
    }

    @Override
    public void run() {
        ImageInputStream stream = new MemoryCacheImageInputStream(new BufferedInputStream(new DataInputStream(new ByteArrayInputStream(pyramid.getTiles()[a][b].GetBytes())), 102400));
        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
        reader.setInput(stream, true, true);
        BufferedImage bi;
        try {
            bi = reader.read(0);
            AffineTransform at = new AffineTransform();
            at.scale(Pyramid.DownScale,Pyramid.DownScale);
            AffineTransformOp scaleOp =  new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
            BufferedImage target = new BufferedImage((int)(Pyramid.DownScale*bi.getWidth()),(int)(Pyramid.DownScale*bi.getHeight()),bi.getType());
            scaleOp.filter(bi, target);
            pyramid.put(target,a,b);   
        } catch (IOException ex) {
            Logger.getLogger(SmushProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

class MergeProcessor implements Runnable {
    private final Pyramid pyramid;
    private final int a;
    private final int b;
    private final JPEGBuffer[][] neotiles;
    
    public MergeProcessor(Pyramid pyramid, JPEGBuffer[][] neotiles, int a, int b) {
        this.pyramid = pyramid;
        this.neotiles = neotiles;
        this.a = a;
        this.b = b;
    }
    
    private BufferedImage Merge(BufferedImage nw, BufferedImage ne, BufferedImage sw, BufferedImage se) {
        BufferedImage bi = new BufferedImage(2*pyramid.gettileSizeX(),2*pyramid.gettileSizeY(),nw.getType());
        Graphics g = bi.getGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, bi.getWidth(), bi.getHeight());
        g.drawImage(nw, 0, 0, null);
        if (ne!=null) {
            g.drawImage(ne, nw.getWidth(), 0, null);
        }
        if (sw!=null) {
            g.drawImage(sw, 0, nw.getHeight(), null);
        }
        if (se!=null) {
            g.drawImage(se, nw.getWidth(), nw.getHeight(), null);
        }
        return bi;
    }

    @Override
    public void run() {
        BufferedImage nw = pyramid.getBufferedImage(a,b);
        if (nw!=null) {
            BufferedImage ne = null;
            BufferedImage sw = null;
            BufferedImage se = null;
            if (a+1<pyramid.gettilesX()) {
                ne = pyramid.getTiles()[a+1][b].GetBufferImage();
            }
            if (b+1<pyramid.gettilesY()) {
                sw = pyramid.getTiles()[a][b+1].GetBufferImage();
            }
            if ((a+1<pyramid.gettilesX())&&(b+1<pyramid.gettilesY())) {
                se = pyramid.getTiles()[a+1][b+1].GetBufferImage();
            }
            int nx = a/2;
            int ny = b/2;
            neotiles[nx][ny] = new JPEGBuffer(Merge(nw,ne,sw,se),pyramid.getParameters().quality);
        } else {
            throw new Error("NW TILE NULL!!!");
        }
    }
}
