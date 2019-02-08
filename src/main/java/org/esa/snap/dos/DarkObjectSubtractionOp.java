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
import org.esa.snap.core.util.converters.BooleanExpressionConverter;

import javax.media.jai.Histogram;
import javax.media.jai.JAI;
import javax.media.jai.RenderedOp;
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

    @Parameter(label = "Mask expression for dark object search area", converter = BooleanExpressionConverter.class,
            description = "Mask expression for dark object search area.")
    private String maskExpression;

    @Parameter(label = "Percentile of minimum in image data", valueSet = {"0", "1", "5"},
            description = "Percentile of minimum in image data in percent " +
                    "(the number means how many percent of the image data are lower than detected minimum.")
    private int histogramMinimumPercentile;


    @SourceProduct(description = "Source product containing spectral bands.")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    private final static String TARGET_PRODUCT_NAME = "Dark-Object-Subtraction";
    private final static String TARGET_PRODUCT_TYPE = "dark-object-subtraction";

    private double[] darkObjectValues;

    @Override
    public void initialize() throws OperatorException {
        sourceProduct = getSourceProduct();

        // validation
        if (sourceProduct.isMultiSize()) {
            throw new OperatorException("Cannot (yet) handle multisize products. Consider resampling the product first.");
        }
        if (this.sourceBandNames == null || this.sourceBandNames.length == 0) {
            throw new OperatorException("Please select at least one source band.");
        }

        GeoCoding sourceGeoCoding = sourceProduct.getSceneGeoCoding();
        if (sourceGeoCoding == null) {
            throw new OperatorException("Source product has no geo-coding");
        }

        darkObjectValues = new double[sourceBandNames.length];

        // set up target product
        targetProduct = createTargetProduct();

        setTargetProduct(targetProduct);
    }

    @Override
    public void doExecute(ProgressMonitor pm) throws OperatorException {
        try {
            pm.beginTask("Executing dark object subtraction...", 0);
            applyDarkObjectSubtraction(pm);
        } catch (Exception e) {
            throw new OperatorException(e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

//    @Override
//    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
//        try {
//            for (int i = 0; i < sourceBandNames.length; i++) {
//                final Band sourceBand = sourceProduct.getBand(sourceBandNames[i]);
//                if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
//                    final Band targetBand = targetProduct.getBand(sourceBandNames[i]);
//                    final Tile sourceTile = getSourceTile(sourceBand, targetRectangle);
//                    for (int y = targetRectangle.y; y < targetRectangle.y + targetRectangle.height; y++) {
//                        checkForCancellation();
//                        for (int x = targetRectangle.x; x < targetRectangle.x + targetRectangle.width; x++) {
//                            final double sourceSample = sourceTile.getSampleDouble(x, y);
//                            targetTiles.get(targetBand).setSample(x, y, sourceSample - darkObjectValues[i]);
//                        }
//                    }
//                }
//            }
//        } catch (Exception e) {
//            throw new OperatorException(e);
//        }
//    }

//    @Override
//    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
//        try {
//            for (int i = 0; i < sourceBandNames.length; i++) {
//                final Band sourceBand = sourceProduct.getBand(sourceBandNames[i]);
//                if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
//                    final Band targetBand = targetProduct.getBand(sourceBandNames[i]);
//                    final Tile sourceTile = getSourceTile(sourceBand, targetRectangle);
//                    final ProductData rawSamples = sourceTile.getRawSamples();
//                    short[] correctedArr = new short[rawSamples.getNumElems()];
//                    ProductData correctedSamples = ProductData.createInstance(correctedArr);
//                    for (int j = 0; j < correctedSamples.getNumElems(); j++) {
//                        int corr = rawSamples.getElemIntAt(j) - (int) Math.round(sourceBand.scaleInverse(darkObjectValues[i]));
//                        correctedSamples.setElemIntAt(j, corr);
//                    }
//                    final Tile targetTile = targetTiles.get(targetBand);
//                    targetTile.setRawSamples(correctedSamples);
//                }
//            }
//        } catch (Exception e) {
//            throw new OperatorException(e);
//        }
//    }

    static RenderedOp subtractConstantFromImage(RenderedImage image, double constantValue) {
        // Create the constant values.
        ParameterBlock pb1 = new ParameterBlock();
        pb1.addSource(image);
        double[] constants = new double[1]; // we have one band per image
        constants[0] = constantValue;
        pb1.add(constants);

        // Construct the SubtractConst operation.
        return JAI.create("subtractconst", pb1, null);
    }

    static double getHistogramMinimum(Stx stx) {
        final Histogram h = stx.getHistogram();
        return h.getLowValue()[0];
    }

    static double getHistogramMaximum(Stx stx) {
        final Histogram h = stx.getHistogram();
        return h.getHighValue()[0];
    }

    static double getHistogramMinAtPercentile(Stx stx, int percentile) {
        final Histogram h = stx.getHistogram();
        final double highValue = h.getHighValue()[0];
        final double lowValue = h.getLowValue()[0];
        final int numBins = h.getNumBins(0);

        double sum = 0.0;
        for (int i = 0; i < numBins; i++) {
            final double binValue = lowValue + i*(highValue-lowValue)/(numBins-1);
            sum += h.getBins()[0][i];
            if (sum >= percentile*h.getTotals()[0]/100.0) {
                return binValue;
            }
        }
        return 0;
    }

    private void applyDarkObjectSubtraction(ProgressMonitor pm) {
        for (int i = 0; i < sourceBandNames.length; i++) {
            final String sourceBandName = sourceBandNames[i];
            checkForCancellation();
            System.out.println("sourceBandName = " + sourceBandName);
            Band sourceBand = sourceProduct.getBand(sourceBandName);
            if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
                Stx stx;
                if (maskExpression == null || maskExpression.isEmpty()) {
                    final long t1 = System.currentTimeMillis();
                    System.out.println("computing histogram without Mask...");
                    stx = new StxFactory().create(sourceBand, ProgressMonitor.NULL);
                    final long t2 = System.currentTimeMillis();
                    System.out.println("computation time for stx without Mask: " + (t2 - t1) + " ms");
                } else {
                    final long t1 = System.currentTimeMillis();
                    System.out.println("computing histogram with Mask...");
                    Mask mask = new Mask("m", sourceBand.getRasterWidth(), sourceBand.getRasterHeight(),
                                         Mask.BandMathsType.INSTANCE);
                    Mask.BandMathsType.setExpression(mask, maskExpression);
                    sourceProduct.getMaskGroup().add(mask);
                    stx = new StxFactory().withRoiMask(mask).create(sourceBand, ProgressMonitor.NULL);
                    final long t2 = System.currentTimeMillis();
                    System.out.println("computation time for stx with Mask: " + (t2 - t1) + " ms");
                }
//                darkObjectValues[i] = getHistogramMinimum(stx);
                darkObjectValues[i] = getHistogramMinAtPercentile(stx, histogramMinimumPercentile);
                System.out.println("darkObjectValue for '" + sourceBandName + "' : " + darkObjectValues[i]);

                final RenderedOp subtractedImage = subtractConstantFromImage(sourceBand.getGeophysicalImage(),
                                                                             darkObjectValues[i]);
                targetProduct.getBand(sourceBandName).setSourceImage(subtractedImage);
            }
            pm.worked(1);
        }
    }

    private Product createTargetProduct() {
        final int sceneWidth = sourceProduct.getSceneRasterWidth();
        final int sceneHeight = sourceProduct.getSceneRasterHeight();
        Product targetProduct = new Product(TARGET_PRODUCT_NAME, TARGET_PRODUCT_TYPE, sceneWidth, sceneHeight);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyMasks(sourceProduct, targetProduct);
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());

        for (String sourceBandName : sourceBandNames) {
            Band sourceBand = sourceProduct.getBand(sourceBandName);
            if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
                final Band targetBand = new Band(sourceBand.getName(), ProductData.TYPE_FLOAT32, sceneWidth, sceneHeight);
                targetProduct.addBand(targetBand);
                ProductUtils.copySpectralBandProperties(sourceBand, targetBand);
//                ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
                ProductUtils.copyGeoCoding(sourceBand, targetBand);
                targetBand.setDescription(sourceBand.getDescription());
                targetBand.setUnit(sourceBand.getUnit());
                targetBand.setNoDataValueUsed(sourceBand.isNoDataValueUsed());
                targetBand.setNoDataValue(sourceBand.getNoDataValue());
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
