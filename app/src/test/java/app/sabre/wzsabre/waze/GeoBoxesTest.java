package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Shrinking-box geometry that widens Waze coverage so near-driver alerts aren't thinned. */
public class GeoBoxesTest {

    private static final double EPS = 1e-9;

    @Test
    public void circleToBoxIsCenteredAndSized() {
        double lon = -122.0, lat = 38.0, r = 8000;
        double[] b = GeoBoxes.circleToBox(lon, lat, r);
        // centered on the point
        assertEquals(lon, (b[0] + b[2]) / 2.0, EPS);
        assertEquals(lat, (b[1] + b[3]) / 2.0, EPS);
        // half-height = r / metersPerDegLat
        assertEquals(r / WazeConstants.M_PER_DEG_LAT, (b[3] - b[1]) / 2.0, EPS);
        // box ordering is [lonMin, latMin, lonMax, latMax]
        assertTrue(b[0] < b[2]);
        assertTrue(b[1] < b[3]);
    }

    @Test
    public void shrinkHalvesExtentAroundSameCenter() {
        double[] box = {-122.1, 37.9, -121.9, 38.1};
        double[] s = GeoBoxes.shrink(box, 0.5);
        assertEquals(-122.0, (s[0] + s[2]) / 2.0, EPS);
        assertEquals(38.0, (s[1] + s[3]) / 2.0, EPS);
        assertEquals((box[2] - box[0]) * 0.5, s[2] - s[0], EPS);
        assertEquals((box[3] - box[1]) * 0.5, s[3] - s[1], EPS);
    }

    @Test
    public void shrinkingBoxesProduceProgressivelySmallerZooms() {
        double[][] boxes = GeoBoxes.shrinkingBoxes(-122.0, 38.0, 8000, 5);
        assertEquals(5, boxes.length);
        double prevWidth = Double.MAX_VALUE;
        for (double[] b : boxes) {
            double w = b[2] - b[0];
            assertTrue("each zoom is smaller than the last", w < prevWidth);
            // all centered on the driver
            assertEquals(-122.0, (b[0] + b[2]) / 2.0, EPS);
            assertEquals(38.0, (b[1] + b[3]) / 2.0, EPS);
            prevWidth = w;
        }
        // each step is half the previous
        assertEquals((boxes[0][2] - boxes[0][0]) * 0.5, boxes[1][2] - boxes[1][0], EPS);
    }
}
