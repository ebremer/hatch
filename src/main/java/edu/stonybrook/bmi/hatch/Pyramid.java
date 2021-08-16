package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    private BufferedImage[][] tiles;
    private int tilesX;
    private int tilesY;
    private final int tileSizeX;
    private final int tileSizeY;
    public int scale = 0;
    
    public Pyramid(int tilesX, int tilesY, int tileSizeX, int tileSizeY) {
        this.tilesX = tilesX;
        this.tilesY = tilesY;
        this.tileSizeX = tileSizeX;
        this.tileSizeY = tileSizeY;
        tiles = new BufferedImage[tilesX][tilesY];
    }
    
    public int gettilesX() {
        return tilesX;
    }
    
    public int gettilesY() {
        return tilesY;
    }
    
    public BufferedImage[][] getTiles() {
        return tiles;
    }
    
    public void put(BufferedImage bi, int x, int y, float scale) {
        AffineTransform at = new AffineTransform();
        at.scale(scale,scale);
        AffineTransformOp scaleOp =  new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        BufferedImage target = new BufferedImage(bi.getWidth()/2,bi.getHeight()/2,bi.getType());
        scaleOp.filter(bi, target);
        put(target,x,y);
    }
    
    public void Shrink(float scale) {
        for (int a=0; a<tilesX; a++) {
            for (int b=0; b<tilesY; b++) {
                put(tiles[a][b],a,b,scale);
            }
        }
    }
    
    public void put(BufferedImage bi, int x, int y) {
        tiles[x][y] = bi;
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
        g.setColor(Color.BLACK);
        g.clearRect(0, 0, tileSizeX, tileSizeY);
        g.drawImage(nw, 0, 0, null);
        if (ne!=null) {
            g.drawImage(ne, tileSizeX/2, 0, null);
        }
        if (sw!=null) {
            g.drawImage(sw, 0, tileSizeY/2, null);
        }
        if (se!=null) {
            g.drawImage(se, tileSizeX/2, tileSizeY/2, null);
        }
        return bi;
    }
    
    public void Lump() {
        scale++;
        int neotilesX = (int) Math.ceil(tilesX/2f);
        int neotilesY = (int) Math.ceil(tilesY/2f);
        BufferedImage[][] neotiles = new BufferedImage[neotilesX][neotilesY];
        for (int a=0; a<tilesX; a=a+2) {
            for (int b=0; b<tilesY; b=b+2) {
                BufferedImage nw = tiles[a][b];
                BufferedImage ne = null;
                BufferedImage sw = null;
                BufferedImage se = null;
                if (a+1<tilesX) {
                    ne = tiles[a+1][b];
                }
                if (b+1<tilesY) {
                    sw = tiles[a][b+1];
                }
                if ((a+1<tilesX)&&(b+1<tilesY)) {
                    se = tiles[a+1][b+1];
                }               
                int nx = a/2;
                int ny = b/2;
                neotiles[nx][ny] = Merge(nw,ne,sw,se);
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
        param.setCompressionQuality(0.5f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream outputStream = new MemoryCacheImageOutputStream(baos);
        outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        jpgWriter.setOutput(outputStream);
        if (tiles[a][b]!=null) {
            IIOImage outputImage = new IIOImage(tiles[a][b], null, null);
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
        return tiles[a][b];
    }
    
    public void Dump2File(byte[] buffer, int a, int b) {
        try {
            FileOutputStream fos = new FileOutputStream("/vsi/whoa/"+scale+" === "+a+"-"+b+".jpg");
            fos.write(buffer);
            fos.flush();
            fos.close();
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
                param.setCompressionQuality(0.7f);
                FileOutputStream fos = new FileOutputStream("/vsi/whoa/"+scale+"----"+a+"-"+b+".jpg");
                ImageOutputStream stream = ImageIO.createImageOutputStream(fos);
                jpgWriter.setOutput(stream);
                if (tiles[a][b]!=null) {
                    IIOImage outputImage = new IIOImage(tiles[a][b], null, null);
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