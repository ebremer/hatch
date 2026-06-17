package edu.stonybrook.bmi.hatch;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.UUID;
import static org.apache.commons.codec.binary.Base64.encodeBase64;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFWriterBuilder;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.XSD;

/**
 *
 * @author erich
 */
public class XMP {
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

    public byte[] getXMP() {
        Model m = ModelFactory.createDefaultModel();
        Resource root = m.createResource(uuid);
        if (magnification != null) {
            DecimalFormat f = new DecimalFormat("#.##############################");
            f.setDecimalSeparatorAlwaysShown(false);
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/ObjectiveLensPower"), f.format(magnification));
        }
        if (manufacturer != null) {
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/Manufacturer"), manufacturer);
        }
        if (manufacturerdevicename != null) {
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/ManufacturerModelName"), manufacturerdevicename);
        }
        if (iccprofile != null) {
            Literal lit = m.createTypedLiteral(encodeBase64(iccprofile), XSDDatatype.XSDbase64Binary);
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/ICCProfile"), lit);
        }
        if (ImageComments != null) {
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/ImageComments"), ImageComments);
        }
        if (exposuretime != null) {
            DecimalFormat f = new DecimalFormat("#.##############################");
            f.setDecimalSeparatorAlwaysShown(false);
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/ExposureTime"), m.createLiteral(f.format(exposuretime)));
        }
        if ((ppsx != null) && (ppsy != null)) {
            DecimalFormat f = new DecimalFormat("#.##############################");
            f.setDecimalSeparatorAlwaysShown(false);
            root.addProperty(m.createProperty("http://ns.adobe.com/DICOM/PixelSpacing"), m.createList(m.createLiteral(f.format(ppsy)), m.createLiteral(f.format(ppsx))));
        }
        m.setNsPrefix("DICOM", "http://ns.adobe.com/DICOM/");
        m.setNsPrefix("rdf", RDF.uri);
        m.setNsPrefix("xmpMM", "http://ns.adobe.com/xap/1.0/mm/");
        m.setNsPrefix("xmp", "http://ns.adobe.com/xap/1.0/");
        m.setNsPrefix("xsd", XSD.NS);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        RDFWriterBuilder builder = RDFWriterBuilder.create();
        builder
            .source(m)
            .lang(Lang.RDFXML)
            .base(uuid)
            .output(os);
        builder.build();
        return os.toByteArray();
    }

    public String getXMPString() {
        String packet = new String(getXMP(), StandardCharsets.UTF_8);
        packet = "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n<x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='" + Hatch.software + "'>\n" + packet;
        packet = packet + "</x:xmpmeta>\n" + (new String(new char[2424]).replace('\0', ' ')) + "\n<?xpacket end='w'?>";
        return packet;
    }
}
