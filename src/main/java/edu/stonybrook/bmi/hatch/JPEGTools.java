package edu.stonybrook.bmi.hatch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *
 * @author erich
 */
public class JPEGTools {
    static byte FF = (byte) 0xff;
    static byte D9 = (byte) 0xd9;
    private static final String JPEG_METADATA = "javax_imageio_jpeg_image_1.0";

    /**
     * What a JPEG stream's frame header and colour markers declare.
     *
     * @param components   number of colour components
     * @param hSubsampling horizontal luma:chroma sampling ratio (TIFF YCbCrSubSampling[0])
     * @param vSubsampling vertical luma:chroma sampling ratio (TIFF YCbCrSubSampling[1])
     * @param rgb          true if the components are stored as RGB, false if as YCbCr
     */
    public record JpegInfo(int components, int hSubsampling, int vSubsampling, boolean rgb) {}

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

    /**
     * Reads the frame header and colour markers of a JPEG stream. The colour space is decided
     * the way libjpeg decides it: a JFIF marker means YCbCr, else an Adobe marker's transform
     * (0 = RGB), else the component IDs ('R','G','B' = RGB).
     *
     * @throws FormatException if the stream is not a baseline or extended-sequential JPEG
     */
    public static JpegInfo inspect(byte[] jpeg) throws FormatException {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xff) != 0xFF || (jpeg[1] & 0xff) != 0xD8) {
            throw new FormatException("Tile is not a JPEG stream (no SOI marker)");
        }
        boolean jfif = false;
        int adobeTransform = -1;
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xff) != 0xFF) {
                throw new FormatException("Corrupt JPEG stream: expected a marker at byte " + pos);
            }
            int marker = jpeg[pos + 1] & 0xff;
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2; // standalone marker, no length field
                continue;
            }
            if (marker == 0xD9) {
                break; // end of image before any frame header
            }
            int length = ((jpeg[pos + 2] & 0xff) << 8) | (jpeg[pos + 3] & 0xff);
            int payload = pos + 4;
            if (payload + length - 2 > jpeg.length) {
                throw new FormatException("Corrupt JPEG stream: segment at byte " + pos + " runs past the end");
            }
            if (marker == 0xE0 && length >= 7 && startsWith(jpeg, payload, "JFIF\0")) {
                jfif = true;
            } else if (marker == 0xEE && length >= 14 && startsWith(jpeg, payload, "Adobe")) {
                adobeTransform = jpeg[payload + 11] & 0xff;
            } else if (marker == 0xC0 || marker == 0xC1) {
                int components = jpeg[payload + 5] & 0xff;
                if (length < 8 + 3 * components) {
                    throw new FormatException("Corrupt JPEG frame header at byte " + pos);
                }
                if (components != 3) {
                    return new JpegInfo(components, 1, 1, false);
                }
                int[] id = new int[3];
                int[] h = new int[3];
                int[] v = new int[3];
                for (int i = 0; i < 3; i++) {
                    int spec = payload + 6 + 3 * i;
                    id[i] = jpeg[spec] & 0xff;
                    h[i] = (jpeg[spec + 1] & 0xff) >> 4;
                    v[i] = jpeg[spec + 1] & 0x0f;
                }
                boolean rgb;
                if (jfif) {
                    rgb = false;
                } else if (adobeTransform >= 0) {
                    rgb = adobeTransform == 0;
                } else {
                    rgb = id[0] == 'R' && id[1] == 'G' && id[2] == 'B';
                }
                return new JpegInfo(3, Math.max(1, h[0] / Math.max(1, h[1])), Math.max(1, v[0] / Math.max(1, v[1])), rgb);
            } else if ((marker >= 0xC2 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                throw new FormatException(String.format(
                    "Tile uses an unsupported JPEG process (SOF marker 0x%02X); only baseline JPEG can be copied", marker));
            } else if (marker == 0xDA) {
                break; // start of scan: the frame header should have come first
            }
            pos = payload + length - 2;
        }
        throw new FormatException("JPEG stream has no frame header (SOF marker)");
    }

    private static boolean startsWith(byte[] b, int at, String text) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        return at + t.length <= b.length && Arrays.equals(b, at, at + t.length, t, 0, t.length);
    }

    /**
     * Encodes a tile of one solid colour whose JPEG structure (colour transform and sampling
     * factors) matches {@code like}, so it can stand in for tiles a source does not store.
     */
    public static byte[] blankTile(int width, int height, int rgb, JpegInfo like, float quality) throws IOException {
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(bi), param);
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(JPEG_METADATA);
            if (like.rgb()) {
                // JFIF implies YCbCr; an Adobe marker with transform 0 stores RGB unconverted
                Node variety = root.getElementsByTagName("JPEGvariety").item(0);
                while (variety.hasChildNodes()) {
                    variety.removeChild(variety.getFirstChild());
                }
                IIOMetadataNode adobe = new IIOMetadataNode("app14Adobe");
                adobe.setAttribute("version", "100");
                adobe.setAttribute("flags0", "0");
                adobe.setAttribute("flags1", "0");
                adobe.setAttribute("transform", "0");
                Node markers = root.getElementsByTagName("markerSequence").item(0);
                markers.insertBefore(adobe, markers.getFirstChild());
            }
            NodeList specs = root.getElementsByTagName("componentSpec");
            for (int i = 0; i < specs.getLength(); i++) {
                IIOMetadataNode spec = (IIOMetadataNode) specs.item(i);
                boolean luma = i == 0 && !like.rgb();
                spec.setAttribute("HsamplingFactor", String.valueOf(luma ? like.hSubsampling() : 1));
                spec.setAttribute("VsamplingFactor", String.valueOf(luma ? like.vSubsampling() : 1));
            }
            meta.setFromTree(JPEG_METADATA, root);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(bi, null, meta), param);
            }
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    public static byte[] Dump2ByteArray(String src, BufferedImage bi, float compression) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter jpgWriter = (ImageWriter) ImageIO.getImageWritersByFormatName("jpg").next();
        try {
            ImageWriteParam param = jpgWriter.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
            param.setCompressionQuality(compression);
            MemoryCacheImageOutputStream outputStream = new MemoryCacheImageOutputStream(baos);
            outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            jpgWriter.setOutput(outputStream);
            jpgWriter.write(null, new IIOImage(bi, null, null), param);
        } catch (IOException ex) {
            throw new UncheckedIOException("JPEG encoding failed for " + src, ex);
        } finally {
            jpgWriter.dispose();
        }
        return baos.toByteArray();
    }
}
