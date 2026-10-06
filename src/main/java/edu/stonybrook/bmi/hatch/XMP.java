package edu.stonybrook.bmi.hatch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * The XMP packet hatch embeds in the full-resolution image (TIFF tag 700).
 *
 * <p>The RDF/XML is written directly, in the layout Apache Jena's RDF/XML writer used to
 * produce, so readers of earlier hatch output keep working: properties in the
 * {@code http://ns.adobe.com/DICOM/} namespace, and PixelSpacing as an RDF list of
 * (row spacing, column spacing) in mm.
 *
 * @author erich
 */
public class XMP {
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private BigDecimal magnification = null;
    private BigDecimal ppsx = null;
    private BigDecimal ppsy = null;
    private final String uuid;
    private String manufacturer = null;
    private String manufacturerdevicename = null;
    private byte[] iccprofile = null;
    private String ImageComments = null;
    private BigDecimal exposuretime = null;

    public XMP() {
        this.uuid = "urn:uuid:" + UUID.randomUUID().toString();
    }

    public String getUUID() {
        return uuid;
    }

    public void setMagnification(BigDecimal magnification) {
        this.magnification = magnification;
    }

    public void setExposureTime(BigDecimal s) {
        this.exposuretime = s;
    }

    public void setManufacturer(String s) {
        this.manufacturer = s;
    }

    public void setImageComments(String s) {
        this.ImageComments = s;
    }

    public void setICCColorProfile(byte[] s) {
        this.iccprofile = s;
    }

    public void setManufacturerDeviceName(String s) {
        this.manufacturerdevicename = s;
    }

    public void setSizePerPixelXinMM(BigDecimal ppsx) {
        this.ppsx = ppsx;
    }

    public void setSizePerPixelYinMM(BigDecimal ppsy) {
        this.ppsy = ppsy;
    }

    /** The RDF/XML document. */
    public byte[] getXMP() {
        StringBuilder x = new StringBuilder();
        x.append("<rdf:RDF\n")
         .append("    xmlns:rdf=\"").append(RDF).append("\"\n")
         .append("    xmlns:DICOM=\"http://ns.adobe.com/DICOM/\"\n")
         .append("    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n")
         .append("    xmlns:xmpMM=\"http://ns.adobe.com/xap/1.0/mm/\"\n")
         .append("    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">\n")
         .append("  <rdf:Description rdf:about=\"").append(escape(uuid)).append("\">\n");
        if ((ppsx != null) && (ppsy != null)) {
            x.append("    <DICOM:PixelSpacing rdf:parseType=\"Resource\">\n")
             .append("      <rdf:rest rdf:parseType=\"Resource\">\n")
             .append("        <rdf:rest rdf:resource=\"").append(RDF).append("nil\"/>\n")
             .append("        <rdf:first>").append(number(ppsx)).append("</rdf:first>\n")
             .append("      </rdf:rest>\n")
             .append("      <rdf:first>").append(number(ppsy)).append("</rdf:first>\n")
             .append("    </DICOM:PixelSpacing>\n");
        }
        if (exposuretime != null) {
            property(x, "ExposureTime", number(exposuretime));
        }
        if (ImageComments != null) {
            property(x, "ImageComments", escape(ImageComments));
        }
        if (iccprofile != null) {
            x.append("    <DICOM:ICCProfile rdf:datatype=\"http://www.w3.org/2001/XMLSchema#base64Binary\">")
             .append(Base64.getEncoder().encodeToString(iccprofile))
             .append("</DICOM:ICCProfile>\n");
        }
        if (manufacturerdevicename != null) {
            property(x, "ManufacturerModelName", escape(manufacturerdevicename));
        }
        if (manufacturer != null) {
            property(x, "Manufacturer", escape(manufacturer));
        }
        if (magnification != null) {
            property(x, "ObjectiveLensPower", number(magnification));
        }
        x.append("  </rdf:Description>\n")
         .append("</rdf:RDF>\n");
        return x.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void property(StringBuilder x, String name, String value) {
        x.append("    <DICOM:").append(name).append('>').append(value).append("</DICOM:").append(name).append(">\n");
    }

    /** Plain decimal notation, independent of the default locale (0.00025, not 2.5E-4 or 0,00025). */
    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Escapes text for XML element content and attribute values. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    // XML 1.0 forbids most control characters, even escaped
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    public String getXMPString() {
        String packet = new String(getXMP(), StandardCharsets.UTF_8);
        packet = "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n<x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='" + escape(Hatch.software) + "'>\n" + packet;
        // padding lets the packet be edited in place
        packet = packet + "</x:xmpmeta>\n" + " ".repeat(2424) + "\n<?xpacket end='w'?>";
        return packet;
    }
}
