package edu.stonybrook.bmi.hatch;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.RandomAccessInputStream;

/**
 *
 * @author erich
 */
public class JPEGTools {
    static byte FF = (byte) 0xff;
    static byte D9 = (byte) 0xd9;
    
    public static void FindEOI(byte[] buf) {
        for (int i=0; i<buf.length; i++) {
            if (Byte.compare(buf[i],FF)==0) {
                if (Byte.compare(buf[i+1],D9)==0) {
                    System.out.println("EOI : "+i+" "+buf.length);
                }
            } else {
            }
        }
    }

    public static int FindFirstEOI(byte[] buf) {
        int i=0;
        while(i<buf.length-1) {
            //System.out.println(i+" "+Integer.toHexString(0xFF&buf[i]));
            if (Byte.compare(buf[i],FF)==0) {
                //System.out.println("FF DETECTED");
                if (Byte.compare(buf[i+1],D9)==0) {
                    //System.out.println("EOI : "+i+" "+buf.length);
                    return i+1;
                }
            }
            i++;
        }
        return -1;
    }
    
    public static long FindFirstEOI(RandomAccessInputStream ets) throws IOException {
        long begin = ets.getFilePointer();
        byte first = ets.readByte();
        byte second = ets.readByte();
        while(ets.getFilePointer()<ets.length()) {
            if (Byte.compare(first,FF)==0) {
                if (Byte.compare(second,D9)==0) {
                    long end  = ets.getFilePointer();
                    ets.seek(begin);
                    return end;
                }
            }
            first = second;
            second = ets.readByte();
        }
        return -1;
    }
    
    public static byte[] GetJPG(RandomAccessInputStream ets, long end) throws IOException {
        long begin = ets.getFilePointer();
        byte[] buffer = new byte[(int)(end-begin)];
        ets.read(buffer);
        return buffer;
    }
    
    public static void dump(byte[] buf) {
        
    }
    
    public static void split(byte[] buf, int c) {
        try {
            FileOutputStream fos = new FileOutputStream("/vsi/RAH-"+c+".jpg");
            fos.write(buf);
            fos.close();
        } catch (FileNotFoundException ex) {
            Logger.getLogger(JPEGTools.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(JPEGTools.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}