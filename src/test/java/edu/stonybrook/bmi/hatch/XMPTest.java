package edu.stonybrook.bmi.hatch;

import java.math.BigDecimal;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** The XMP packet must stay exactly what earlier (Apache Jena based) versions wrote. */
class XMPTest {

    /** The packet hatch 4.3.0 wrote for PC380089.vsi, with its UUID replaced by "UUID". */
    private static final String JENA_PACKET = "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n"
        + "<x:xmpmeta xmlns:x='adobe:ns:meta/' x:xmptk='" + Hatch.software + "'>\n"
        + "<rdf:RDF\n"
        + "    xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
        + "    xmlns:DICOM=\"http://ns.adobe.com/DICOM/\"\n"
        + "    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n"
        + "    xmlns:xmpMM=\"http://ns.adobe.com/xap/1.0/mm/\"\n"
        + "    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">\n"
        + "  <rdf:Description rdf:about=\"UUID\">\n"
        + "    <DICOM:PixelSpacing rdf:parseType=\"Resource\">\n"
        + "      <rdf:rest rdf:parseType=\"Resource\">\n"
        + "        <rdf:rest rdf:resource=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#nil\"/>\n"
        + "        <rdf:first>0.000173459328139</rdf:first>\n"
        + "      </rdf:rest>\n"
        + "      <rdf:first>0.000173458076868</rdf:first>\n"
        + "    </DICOM:PixelSpacing>\n"
        + "    <DICOM:ExposureTime>7.4799999999999995</DICOM:ExposureTime>\n"
        + "    <DICOM:ManufacturerModelName>VC50</DICOM:ManufacturerModelName>\n"
        + "    <DICOM:Manufacturer>Olympus Soft Imaging Solutions</DICOM:Manufacturer>\n"
        + "    <DICOM:ObjectiveLensPower>40</DICOM:ObjectiveLensPower>\n"
        + "  </rdf:Description>\n"
        + "</rdf:RDF>\n"
        + "</x:xmpmeta>\n"
        + " ".repeat(2424) + "\n"
        + "<?xpacket end='w'?>";

    private static XMP pc380089() {
        XMP xmp = new XMP();
        xmp.setSizePerPixelXinMM(new BigDecimal("0.000173459328139"));
        xmp.setSizePerPixelYinMM(new BigDecimal("0.000173458076868"));
        xmp.setExposureTime(BigDecimal.valueOf(7.4799999999999995));
        xmp.setManufacturerDeviceName("VC50");
        xmp.setManufacturer("Olympus Soft Imaging Solutions");
        xmp.setMagnification(BigDecimal.valueOf(40.0));
        return xmp;
    }

    @Test
    void packetIsByteForByteWhatJenaWrote() {
        XMP xmp = pc380089();
        assertEquals(JENA_PACKET, xmp.getXMPString().replace(xmp.getUUID(), "UUID"));
    }

    @Test
    void numbersDoNotDependOnTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY); // decimal comma
            XMP xmp = pc380089();
            assertEquals(JENA_PACKET, xmp.getXMPString().replace(xmp.getUUID(), "UUID"));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void textIsEscaped() {
        XMP xmp = new XMP();
        xmp.setManufacturer("A&B <scanners> \"x\"\u0001");
        String packet = xmp.getXMPString();
        assertTrue(packet.contains("<DICOM:Manufacturer>A&amp;B &lt;scanners&gt; &quot;x&quot;</DICOM:Manufacturer>"), packet);
    }
}
