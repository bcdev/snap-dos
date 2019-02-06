package org.esa.snap.dos;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;

import javax.media.jai.*;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;

/**
 * Performs dark object subtraction for spectral bands in source product.
 *
 * @author olafd
 */
@OperatorMetadata(alias = "Snap.DarkObjectSubtraction",
        version = "1.0-SNAPSHOT",
//        internal = true,
        authors = "Olaf Danne",
        copyright = "(c) 2019 by Brockmann Consult",
        description = "Performs dark object subtraction for spectral bands in source product.")
public class DarkObjectSubtractionOp extends Operator {

    @Parameter(label = "Source bands",
            description = "The source bands to be considered for the dark object subtraction.",
            rasterDataNodeType = Band.class)
    private String[] sourceBandNames;

    @SourceProduct(description = "Source product containing spectral bands.")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    private final static String TARGET_PRODUCT_NAME = "Dark-Object-Subtraction";
    private final static String TARGET_PRODUCT_TYPE = "dark-object-subtraction";

    @Override
    public void initialize() throws OperatorException {
        sourceProduct = getSourceProduct();

        // validation
        if (this.sourceBandNames == null || this.sourceBandNames.length == 0) {
            throw new OperatorException("Please select at least one source band.");
        }

        GeoCoding sourceGeoCoding = sourceProduct.getSceneGeoCoding();
        if (sourceGeoCoding == null) {
            throw new OperatorException("Source product has no geo-coding");
        }

        // set up target product
        targetProduct = createTargetProduct();

        for (String sourceBandName : sourceBandNames) {
            System.out.println("sourceBandName = " + sourceBandName);
            Band sourceBand = sourceProduct.getBand(sourceBandName);
            if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
                final long t1 = System.currentTimeMillis();
                System.out.println("computing histogram...");
                final Stx stx = new StxFactory().create(sourceBand, ProgressMonitor.NULL);
                final double imageMinValue = getHistogramMinimum(stx);
                System.out.println("imageMinValue = " + imageMinValue);
                final long t2 = System.currentTimeMillis();
                System.out.println("computation time for imageMinValue: " + (t2-t1) + " ms");
                final double scaledImageMinValue = sourceBand.scaleInverse(imageMinValue);
                final RenderedOp subtractedImage = subtractConstantFromImage(sourceBand.getSourceImage(), scaledImageMinValue);
                final long t3 = System.currentTimeMillis();
                System.out.println("computation time for subtracting constant: " + (t3-t2) + " ms");
                targetProduct.getBand(sourceBandName).setSourceImage(subtractedImage);
            }
        }
        
        setTargetProduct(targetProduct);
    }

    /* package local for testing */
    static double[] getImageMinMaxValues(PlanarImage image, int inclusionThreshold) {
        ParameterBlock pb = new ParameterBlock();
        pb.addSource(image);   // The source image
        // this means that pixel values < inclusionThreshold are not considered:
        pb.add(new ROI(image, inclusionThreshold));
        pb.add(1);          // check every pixel horizontally
        pb.add(1);          // check every pixel vertically

        // Perform the extrema operation on the source image.
        RenderedImage minMaxImage = JAI.create("extrema", pb, null);

        // Retrieve the min and max pixel values.
        // (these values are both 0 if  inclusionThreshold is greater than maximum of the image)
        final double[][] extrema = (double[][]) minMaxImage.getProperty("extrema");

        return new double[]{extrema[0][0], extrema[1][0]};   // max is extrema[1], min is extrema[0]
    }

    static RenderedOp subtractConstantFromImage(RenderedImage image, double constantValue) {
        // Create the constant values.
        ParameterBlock pb;

        pb = new ParameterBlock();
        pb.addSource(image);
        double[] constants = new double[1]; // we have one band per image
        constants[0] = constantValue;
        pb.add(constants);

        // Construct the SubtractConst operation.
        return JAI.create("subtractconst", pb, null);
    }

    static double getHistogramMinimum(Stx stx) {
        final Histogram h = stx.getHistogram();
        return h.getLowValue()[0];
    }

    private Product createTargetProduct() {
        final int sceneWidth = sourceProduct.getSceneRasterWidth();
        final int sceneHeight = sourceProduct.getSceneRasterHeight();
        Product targetProduct = new Product(TARGET_PRODUCT_NAME, TARGET_PRODUCT_TYPE, sceneWidth, sceneHeight);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());

        for (String sourceBandName : sourceBandNames) {
            Band sourceBand = sourceProduct.getBand(sourceBandName);
            if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
                final Band targetBand = new Band(sourceBand.getName(), sourceBand.getDataType(),
                                                 sourceBand.getRasterWidth(), sourceBand.getRasterHeight());
                targetProduct.addBand(targetBand);
                ProductUtils.copySpectralBandProperties(sourceBand, targetBand);
                ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
                ProductUtils.copyGeoCoding(sourceBand, targetBand);
            } else {
                ProductUtils.copyBand(sourceBand.getName(), sourceProduct, targetProduct, true);
            }
        }
        return targetProduct;
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(DarkObjectSubtractionOp.class);
        }
    }
}
