/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.stonybrook.bmi.hatch;

import java.io.IOException;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.services.OMEXMLService;
import ome.units.quantity.Length;

/**
 *
 * @author erich
 */
public class Scratch {
    
    public static void main(String[] args) throws FormatException, IOException, DependencyException, ServiceException {
        loci.common.DebugTools.setRootLevel("WARN");
        loci.formats.in.CellSensReader reader = new loci.formats.in.CellSensReader();
        ServiceFactory factory = new ServiceFactory();
        OMEXMLService service = factory.getInstance(OMEXMLService.class);
        IMetadata omexml = service.createOMEXMLMetadata();
        reader.setMetadataStore(omexml);
        reader.setId("/vsi/B230056.vsi");
        reader.setSeries(7);
        MetadataRetrieve retrieve = (MetadataRetrieve) reader.getMetadataStore();
        Length ppx = retrieve.getPixelsPhysicalSizeX(7);
        Length ppy = retrieve.getPixelsPhysicalSizeY(7);
        System.out.println(ppx.value());
        System.out.println(ppx.unit());
        System.out.println(ppx);
    }
    
}
