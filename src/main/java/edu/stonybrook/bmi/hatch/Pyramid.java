package edu.stonybrook.bmi.hatch;

import java.awt.Graphics;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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
    private final float CompressionSize = 0.7f;
    
    public Pyramid(int tilesX, int tilesY, int tileSizeX, int tileSizeY) {
        this.tilesX = tilesX;
        this.tilesY = tilesY;
        this.tileSizeX = tileSizeX;
        this.tileSizeY = tileSizeY;
        tiles = new JPEGBuffer[tilesX][tilesY];
    }
    
    public int gettilesX() {
        return tilesX;
    }
    
    public int gettilesY() {
        return tilesY;
    }

    public void put(byte[] raw, int x, int y, int effTileSizeX, int effTileSizeY, float scale) throws IOException {
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raw));
        bi = bi.getSubimage(0, 0, effTileSizeX, effTileSizeY);
        put(bi, x, y);
    }
    
    public void put(BufferedImage bi, int x, int y, float scale) {
        //System.out.println("put(BufferedImage bi, int x, int y, float scale) "+bi.getWidth()+" "+bi.getHeight()+" "+x+" "+y+" "+scale);
        AffineTransform at = new AffineTransform();
        at.scale(scale,scale);
        AffineTransformOp scaleOp =  new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        BufferedImage target = new BufferedImage(bi.getWidth()/2,bi.getHeight()/2,bi.getType());
        scaleOp.filter(bi, target);
        put(target,x,y);
    }
    
    public void Shrink(float scale) {
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
                try {
                    put(ImageIO.read(new ByteArrayInputStream(tiles[a][b].GetBytes())),a,b,scale);
                } catch (IOException ex) {
                    Logger.getLogger(Pyramid.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }
    
    public void put(BufferedImage bi, int x, int y) {
        tiles[x][y] = new JPEGBuffer(bi,CompressionSize);
    }
    
    public BufferedImage Merge(BufferedImage nw, BufferedImage ne, BufferedImage sw, BufferedImage se) {
        int width = nw.getWidth();
        int height = nw.getHeight();
        if (ne!=null) {
            width = width + ne.getWidth();
        }
        if (sw!=null) {
            height = height + sw.getHeight();
        }
        BufferedImage bi = new BufferedImage(width,height,nw.getType());
        Graphics g = bi.getGraphics();
        g.drawImage(nw, 0, 0, null);
        if (ne!=null) {
            g.drawImage(ne, tileSizeX, 0, null);
        }
        if (sw!=null) {
            g.drawImage(sw, 0, tileSizeY, null);
        }
        if (se!=null) {
            g.drawImage(se, tileSizeX, tileSizeY, null);
        }
        return bi;
    }
    
    public void Lump() {
        xscale++;
        int neotilesX = (int) Math.ceil(tilesX/2f);
        int neotilesY = (int) Math.ceil(tilesY/2f);
        JPEGBuffer[][] neotiles = new JPEGBuffer[neotilesX][neotilesY];
        for (int a=0; a<tilesX; a=a+2) {
            for (int b=0; b<tilesY; b=b+2) {
                BufferedImage nw = getBufferedImage(a,b);
                if (nw!=null) {
                    BufferedImage ne = null;
                    BufferedImage sw = null;
                    BufferedImage se = null;
                    if (a+1<tilesX) {
                        ne = tiles[a+1][b].GetBufferImage();
                    }
                    if (b+1<tilesY) {
                        sw = tiles[a][b+1].GetBufferImage();
                    }
                    if ((a+1<tilesX)&&(b+1<tilesY)) {
                        se = tiles[a+1][b+1].GetBufferImage();
                    }               
                    int nx = a/2;
                    int ny = b/2;
                    neotiles[nx][ny] = new JPEGBuffer(Merge(nw,ne,sw,se),CompressionSize);
                }
            }
        }
        tiles = neotiles;
        tilesX = neotilesX;
        tilesY = neotilesY;
    }
    
    public byte[] GetImageBytes(int a, int b) {
        ImageWriter jpgWriter = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = jpgWriter.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        param.setCompressionQuality(CompressionSize);
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
                param.setCompressionQuality(CompressionSize);
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
}