package org.esa.snap.dos;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import junit.framework.TestCase;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorException;
import org.junit.Ignore;

import javax.media.jai.RenderedOp;
import java.awt.image.DataBuffer;

public class DarkObjectSubtractionOpTest extends TestCase {

    private Band targetBand1;

    private int width;
    private int height;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        width = 4;
        height = 3;
        final Product product = new Product("p1", "t", width, height);
        targetBand1 = product.addBand("b1", ProductData.TYPE_FLOAT32);

        targetBand1.setDataElems(new float[]{
                2, 3, Float.NaN, 5,
                6, Float.NaN, 8, 9,
                10, 11, 12, 13
        });

    }

    @Ignore
    public void testSubtractConstantFromImage() {
        final double constValue = 5.0;
        final MultiLevelImage origImage = targetBand1.getGeophysicalImage();
        final RenderedOp subtractedImage = DarkObjectSubtractionOp.subtractConstantFromImage(origImage,
                                                                                             constValue,
                                                                                             0.0, 1.0);
        Band testBand = new Band("test", targetBand1.getDataType(), width, height);
        testBand.setSourceImage(subtractedImage);
        final float[] dataElems = (float[]) targetBand1.getDataElems();
        assertNotNull(dataElems);
        final DataBuffer subtractedDataBuffer = subtractedImage.getData().getDataBuffer();
        assertNotNull(subtractedDataBuffer);
        for (int i = 0; i < dataElems.length; i++) {
            final int subtractedDataElem = subtractedDataBuffer.getElem(i);
            if (!Float.isNaN((dataElems[i]))) {
                if (dataElems[i] - constValue >= 0.0) {
                    assertEquals(dataElems[i] - constValue, subtractedDataElem, 1.E-6);
                } else {
                    assertEquals(0.0, subtractedDataElem, 1.E-6);
                }
            }
        }
    }

    @Ignore
    public void testSubtractConstantFromImage_2() {
        Band testBand = new Band("test", targetBand1.getDataType(), width, height);
        testBand.setScalingFactor(1.E-4);
        final float[] dataElems = new float[]{
                200, 300, Float.NaN, 500,
                600, Float.NaN, 800, 900,
                1000, 1100, 1200, 1300
        };
        testBand.setDataElems(dataElems);

        final double constValue = 0.1;
        final double constValueRescaled = testBand.scaleInverse(constValue);
        final double clampedMinValue = 1.E-6;
        final MultiLevelImage origImage = testBand.getGeophysicalImage();
        final RenderedOp subtractedImage = DarkObjectSubtractionOp.subtractConstantFromImage(origImage,
                                                                                             constValue,
                                                                                             clampedMinValue,
                                                                                             testBand.getScalingFactor());

        assertNotNull(dataElems);
        final DataBuffer subtractedDataBuffer = subtractedImage.getData().getDataBuffer();
        assertNotNull(subtractedDataBuffer);
        for (int i = 0; i < dataElems.length; i++) {
            final int subtractedDataElem = subtractedDataBuffer.getElem(i);
            if (!Float.isNaN((dataElems[i]))) {
                if (dataElems[i] - constValueRescaled >= 0.0) {
                    assertEquals(dataElems[i] - constValueRescaled, subtractedDataElem, 1.E-0);
                } else {
                    assertEquals(0.0, subtractedDataElem, 1.E-6);
                }
            }
        }
    }

    public void testGetHistogramMinumum() {
        final double offset = 1.3;
        final Product product = new Product("F", "F", 100, 100);
        final Band band = new VirtualBand("V", ProductData.TYPE_FLOAT32, 100, 100, "(X-0.5) + (Y-0.5) + " + offset);
        product.addBand(band);
        final Stx stx = new StxFactory().create(band, ProgressMonitor.NULL);
        final double histoMin = DarkObjectSubtractionOp.getHistogramMinimum(stx);
        System.out.println("histoMin = " + histoMin);
        assertEquals(1.3, histoMin, 1.E-6);
    }

    public void testGetHistogramMinumumWithRoiMask() {
        final double offset = 1.3;
        final Product product = new Product("F", "F", 100, 100);
        final Band band = new VirtualBand("V", ProductData.TYPE_FLOAT32, 100, 100, "(X-0.5) + (Y-0.5) + " + offset);
        product.addBand(band);

        Mask mask = new Mask("m", 100, 100, Mask.BandMathsType.INSTANCE);
        Mask.BandMathsType.setExpression(mask, "X >= 10 && Y >= 10");
        product.getMaskGroup().add(mask);

        final Stx stx = new StxFactory().withRoiMask(mask).create(band, ProgressMonitor.NULL);
        final double histoMin = DarkObjectSubtractionOp.getHistogramMinimum(stx);
        System.out.println("histoMinWithRoiMask = " + histoMin);
        assertEquals(21.3, histoMin, 1.E-6);
    }

}
