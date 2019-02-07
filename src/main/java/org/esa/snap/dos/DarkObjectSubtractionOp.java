package org.esa.snap.dos;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.converters.RectangleConverter;

import javax.media.jai.Histogram;
import javax.media.jai.JAI;
import javax.media.jai.RenderedOp;
import java.awt.*;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.util.Map;

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

    @Parameter(converter = RectangleConverter.class,
            label = "Subset region for dark object search",
            description = "The subset region for dark object search in pixel coordinates.\n" +
                    "Use the following format: <x>,<y>,<width>,<height>\n" +
                    "If not given, the entire scene is used.")
    private Rectangle darkObjectSearchRegion = null;

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

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
            try {
                for (int i = 0; i < sourceBandNames.length; i++) {
                    final Band sourceBand = sourceProduct.getBand(sourceBandNames[i]);
                    if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
                        final Tile sourceTile = getSourceTile(sourceBand, targetRectangle);
                        for (int y = targetRectangle.y; y < targetRectangle.y + targetRectangle.height; y++) {
                            checkForCancellation();
                            for (int x = targetRectangle.x; x < targetRectangle.x + targetRectangle.width; x++) {
                                final String sourceBandName = sourceBandNames[i];
                                checkForCancellation();
                                final Band targetBand = targetProduct.getBand(sourceBandName);

                                final double sourceSample = sourceTile.getSampleDouble(x, y);
                                if (Double.isNaN(sourceSample)) {
                                    targetTiles.get(targetBand).setSample(x, y, Double.NaN);
                                } else {
                                    final double resultSample = sourceSample - darkObjectValues[i];
                                    if (resultSample <= 0.0) {
                                        targetTiles.get(targetBand).setSample(x, y, sourceSample);
                                    } else {
                                        targetTiles.get(targetBand).setSample(x, y, resultSample);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                throw new OperatorException(e);
            }
        }

        static RenderedOp subtractConstantFromImage (RenderedImage image,
        double constantValue,
        double clampedMinValue,
        double scaleFactor){
            // Create the constant values.
            ParameterBlock pb1 = new ParameterBlock();
            pb1.addSource(image);
            double[] constants = new double[1]; // we have one band per image
            constants[0] = constantValue;
            pb1.add(constants);

            // Construct the SubtractConst operation.
            final RenderedOp subtractedImage = JAI.create("subtractconst", pb1, null);

            // clamp subtracted result to 0.0 (no negative values) TODO: discuss how to handle this
            ParameterBlock pb2 = new ParameterBlock();
            pb2.addSource(subtractedImage);
            double[] low = new double[1];
            double[] high = new double[1];
            low[0] = clampedMinValue;
            high[0] = Double.MAX_VALUE;
            pb2.add(low);
            pb2.add(high);

            // Construct the Clamp operation.
            final RenderedOp clampedImage = JAI.create("clamp", pb2, null);

            // Construct the Rescale operation.
            ParameterBlock pb3 = new ParameterBlock();
            pb3.addSource(clampedImage);
            constants = new double[1]; // we have one band per image
            constants[0] = 1.0 / scaleFactor;
            pb3.add(constants);

            final RenderedOp rescaledImage = JAI.create("multiplyconst", pb3, null);
            return rescaledImage;
        }

        static double getHistogramMinimum (Stx stx){
            final Histogram h = stx.getHistogram();
            return h.getLowValue()[0];
        }

        private void applyDarkObjectSubtraction (ProgressMonitor pm){
            for (int i = 0; i < sourceBandNames.length; i++) {
                final String sourceBandName = sourceBandNames[i];
                checkForCancellation();
                System.out.println("sourceBandName = " + sourceBandName);
                Band sourceBand = sourceProduct.getBand(sourceBandName);
                if (sourceBand.getSpectralBandIndex() >= 0 && !Float.isNaN(sourceBand.getSpectralWavelength())) {
                    Stx stx;
                    if (darkObjectSearchRegion == null || darkObjectSearchRegion.isEmpty()) {
                        final long t1 = System.currentTimeMillis();
                        System.out.println("computing histogram without ROI...");
                        stx = new StxFactory().create(sourceBand, ProgressMonitor.NULL);
                        final long t2 = System.currentTimeMillis();
                        System.out.println("computation time for stx without ROI: " + (t2 - t1) + " ms");
                    } else {
                        final long t1 = System.currentTimeMillis();
                        System.out.println("computing histogram with ROI...");
                        System.out.println("darkObjectSearchRegion = " + darkObjectSearchRegion);
                        Mask mask = new Mask("m", sourceBand.getRasterWidth(), sourceBand.getRasterHeight(),
                                             Mask.BandMathsType.INSTANCE);
                        final int rx = darkObjectSearchRegion.x;
                        final int ry = darkObjectSearchRegion.y;
                        final int rw = darkObjectSearchRegion.width;
                        final int rh = darkObjectSearchRegion.height;
                        final String maskExpr =
                                "X >= " + rx + " && X <= " + (rx + rw) + " && Y >= " + ry + " && Y <= " + (ry + rh);
                        Mask.BandMathsType.setExpression(mask, maskExpr);
                        sourceProduct.getMaskGroup().add(mask);
                        stx = new StxFactory().withRoiMask(mask).create(sourceBand, ProgressMonitor.NULL);
                        final long t2 = System.currentTimeMillis();
                        System.out.println("computation time for stx with ROI: " + (t2 - t1) + " ms");
                    }
                    final double imageMinValue = getHistogramMinimum(stx);
                    System.out.println("imageMinValue = " + imageMinValue);
                    darkObjectValues[i] = imageMinValue;

//                final double scaledImageMinValue = sourceBand.scaleInverse(imageMinValue);
//                final double clampedMinValue = sourceBand.scaleInverse(0.0001);
//                final double scaledImageMinValue = imageMinValue;
//                final double clampedMinValue = 1.E-6;
//                final RenderedOp subtractedImage = subtractConstantFromImage(sourceBand.getSourceImage(),
//                                                                             scaledImageMinValue,
//                                                                             clampedMinValue,
//                                                                             sourceBand.getScalingFactor());
//                targetProduct.getBand(sourceBandName).setSourceImage(subtractedImage);
                }
                pm.worked(1);
            }
        }

        private Product createTargetProduct () {
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
