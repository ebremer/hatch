package edu.stonybrook.bmi.hatch;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import loci.common.DataTools;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.codec.BaseCodec;
import loci.formats.codec.CodecOptions;
import loci.formats.codec.LosslessJPEGCodec;
import loci.formats.gui.AWTImageTools;

/**
 *
 * @author erich
 */
public class NeoJPEGCodec extends BaseCodec {

    @Override
    public byte[] compress(byte[] data, CodecOptions options) throws FormatException {
        //System.out.println("custom");
        options.channels = 3;
        BufferedImage bi = AWTImageTools.makeImage(data, options.width,options.height, options.channels, options.interleaved, options.bitsPerSample / 8, false, options.littleEndian, options.signed);
        //System.out.println("MODEL : "+bi.getType());
        ImageWriter jpgWriter = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = jpgWriter.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        param.setCompressionQuality(0.7f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream outputStream = new MemoryCacheImageOutputStream(baos);
        jpgWriter.setOutput(outputStream);
        IIOImage outputImage = new IIOImage(bi, null, null);
        try {
            jpgWriter.write(null, outputImage, param);
        } catch (IOException ex) {
            Logger.getLogger(NeoJPEGCodec.class.getName()).log(Level.SEVERE, null, ex);
        }
        jpgWriter.dispose();
        return baos.toByteArray();
    }   
    
    @Override
    public byte[] decompress(RandomAccessInputStream in, CodecOptions options) throws FormatException, IOException {
        BufferedImage b;
        long fp = in.getFilePointer();
        try {
            try {
                while (in.read() != (byte) 0xff || in.read() != (byte) 0xd8);
                in.seek(in.getFilePointer() - 2);
      }
      catch (EOFException e) {
        in.seek(fp);
      }

      b = ImageIO.read(new BufferedInputStream(new DataInputStream(in), 81920));
    }
    catch (IOException exc) {
      // probably a lossless JPEG; delegate to LosslessJPEGCodec
      in.seek(fp);
      return new LosslessJPEGCodec().decompress(in, options);
    }

    if (options == null) options = CodecOptions.getDefaultOptions();

    int nPixels = b.getWidth() * b.getHeight();
    WritableRaster r = (WritableRaster) b.getRaster();
    //options.ycbcr = true;
    if (!options.ycbcr && r.getDataBuffer() instanceof DataBufferByte && b.getType() == BufferedImage.TYPE_BYTE_GRAY) {
      DataBufferByte bb = (DataBufferByte) r.getDataBuffer();

      if (bb.getNumBanks() == 1) {
        byte[] raw = bb.getData();
        if (options.interleaved || bb.getSize() == nPixels) {
          return raw;
        }
      }
    }

    byte[][] buf = ome.codecs.gui.AWTImageTools.getPixelBytes(b, options.littleEndian);

    // correct for YCbCr encoding, if necessary
    if (options.ycbcr && buf.length == 3) {
      int nBytes = buf[0].length / (b.getWidth() * b.getHeight());
      int mask = (int) (Math.pow(2, nBytes * 8) - 1);
      for (int i=0; i<buf[0].length; i+=nBytes) {
        double y = DataTools.bytesToInt(buf[0], i, nBytes, options.littleEndian);
        double cb = DataTools.bytesToInt(buf[1], i, nBytes, options.littleEndian);
        double cr = DataTools.bytesToInt(buf[2], i, nBytes, options.littleEndian);

        cb = Math.max(0, cb - 128);
        cr = Math.max(0, cr - 128);

        int red = (int) (y + 1.402 * cr);
        int green = (int) (y - 0.34414 * cb - 0.71414 * cr);
        int blue = (int) (y + 1.772 * cb);

        red = (int) (Math.min(red, mask)) & mask;
        green = (int) (Math.min(green, mask)) & mask;
        blue = (int) (Math.min(blue, mask)) & mask;

        DataTools.unpackBytes(red, buf[0], i, nBytes, options.littleEndian);
        DataTools.unpackBytes(green, buf[1], i, nBytes, options.littleEndian);
        DataTools.unpackBytes(blue, buf[2], i, nBytes, options.littleEndian);
      }
    }

    byte[] rtn = new byte[buf.length * buf[0].length];
    if (buf.length == 1) rtn = buf[0];
    else {
      if (options.interleaved) {
        int next = 0;
        for (int i=0; i<buf[0].length; i++) {
          for (int j=0; j<buf.length; j++) {
            rtn[next++] = buf[j][i];
          }
        }
      }
      else {
        for (int i=0; i<buf.length; i++) {
          System.arraycopy(buf[i], 0, rtn, i*buf[0].length, buf[i].length);
        }
      }
    }
    return rtn;
    }
}