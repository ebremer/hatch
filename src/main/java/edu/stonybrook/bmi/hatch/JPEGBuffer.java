package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 *
 * @author erich
 */
public class JPEGBuffer {
    private final byte[] buf;
    
    public JPEGBuffer(byte[] buffer) {
        buf = buffer;
    }
    
    public JPEGBuffer(BufferedImage bi, float compression) {
        buf = JPEGTools.Dump2ByteArray(bi,compression);
    }
    
    public byte[] GetBytes() {
        return buf;
    }
    
    public BufferedImage GetBufferImage() {
        BufferedImage bi = null;
        try {
            bi = ImageIO.read(new ByteArrayInputStream(buf));
        } catch (IOException ex) {
            Logger.getLogger(JPEGBuffer.class.getName()).log(Level.SEVERE, null, ex);
        }
        return bi;
    }
}