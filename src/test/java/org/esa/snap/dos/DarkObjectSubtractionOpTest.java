package org.esa.snap.dos;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import junit.framework.TestCase;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorException;

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

    public void testGetImageMinMax() throws OperatorException {
        // find min and max of image which includes NaN values
        int inclusionThreshold = -10;
        double[] minMaxValues = DarkObjectSubtractionOp.getImageMinMaxValues(targetBand1.getGeophysicalImage(), inclusionThreshold);
        assertEquals(2.0, minMaxValues[0]);
        assertEquals(13.0, minMaxValues[1]);

        // find min and max of image with inclusion threshold greater than minimum of image
        inclusionThreshold = 5;
        minMaxValues = DarkObjectSubtractionOp.getImageMinMaxValues(targetBand1.getGeophysicalImage(), inclusionThreshold);
        assertEquals(6.0, minMaxValues[0]);
        assertEquals(13.0, minMaxValues[1]);

        // find min and max of image with inclusion threshold greater than both minimum and maximum of image
        inclusionThreshold = 17;
        minMaxValues = DarkObjectSubtractionOp.getImageMinMaxValues(targetBand1.getGeophysicalImage(), inclusionThreshold);
        assertEquals(0.0, minMaxValues[0]);
        assertEquals(0.0, minMaxValues[1]);
    }

    public void testSubtractConstantFromImage() {
        final double constValue = 2.0;
        final MultiLevelImage origImage = targetBand1.getGeophysicalImage();
        final RenderedOp subtractedImage = DarkObjectSubtractionOp.subtractConstantFromImage(origImage, constValue);
        Band testBand = new Band("test", targetBand1.getDataType(), width, height);
        testBand.setSourceImage(subtractedImage);
        final float[] dataElems = (float[]) targetBand1.getDataElems();
        assertNotNull(dataElems);
        final DataBuffer subtractedDataBuffer = subtractedImage.getData().getDataBuffer();
        assertNotNull(subtractedDataBuffer);
        for (int i = 0; i < dataElems.length; i++) {
            final int subtractedDataElem = subtractedDataBuffer.getElem(i);
            if (!Float.isNaN((dataElems[i]))) {
                assertEquals(dataElems[i] - constValue, subtractedDataElem, 1.E-6);
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

}
