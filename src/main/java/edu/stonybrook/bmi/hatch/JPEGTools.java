package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import loci.common.RandomAccessInputStream;

/**
 *
 * @author erich
 */
public class JPEGTools {
    static byte FF = (byte) 0xff;
    static byte D9 = (byte) 0xd9;

    public static byte[] FindFirstEOI(RandomAccessInputStream ets, byte[] r) throws IOException {
        long begin = ets.getFilePointer();
        long length = ets.length();
        int c = 0;
        byte prev = 0;
        while (ets.getFilePointer() < length) {
            if (c >= r.length) {
                throw new IOException("JPEG tile starting at offset " + begin
                        + " has no EOI (FFD9) marker within the " + r.length + "-byte buffer");
            }
            byte b = ets.readByte();
            r[c] = b;
            if (c > 0 && Byte.compare(prev, FF) == 0 && Byte.compare(b, D9) == 0) {
                ets.seek(begin);
                return Arrays.copyOf(r, c + 1);
            }
            prev = b;
            c++;
        }
        throw new IOException("Reached end of stream at offset " + begin
                + " before finding JPEG EOI (FFD9) marker");
    }

    public static byte[] Dump2ByteArray(String src, BufferedImage bi, float compression) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter jpgWriter = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = jpgWriter.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        param.setCompressionQuality(compression);
        MemoryCacheImageOutputStream outputStream = new MemoryCacheImageOutputStream(baos);
        outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        jpgWriter.setOutput(outputStream);
        IIOImage outputImage = new IIOImage(bi, null, null);
        try {
            jpgWriter.write(null, outputImage, param);
        } catch (IOException ex) {
            throw new Error(ex.getMessage() + " " + src);
        } catch (Exception ex) {
            throw new Error(ex.getMessage() + " " + src);
        }
        jpgWriter.dispose();
        return baos.toByteArray();
    }
}
