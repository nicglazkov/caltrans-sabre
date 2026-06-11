package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** confirm_ts is inferred as the moment an alert's thumbs-up count last increased. */
public class WazeConfirmTrackerTest {

    @Test
    public void firstSightingHasNoConfirmTs() {
        WazeConfirmTracker t = new WazeConfirmTracker();
        assertNull(t.confirmTsSeconds("a", 3));
    }

    @Test
    public void unchangedCountStaysNull() {
        WazeConfirmTracker t = new WazeConfirmTracker();
        t.confirmTsSeconds("a", 3);
        assertNull("count did not increase", t.confirmTsSeconds("a", 3));
    }

    @Test
    public void increasedCountSetsConfirmTs() {
        WazeConfirmTracker t = new WazeConfirmTracker();
        t.confirmTsSeconds("a", 3);
        Long ts = t.confirmTsSeconds("a", 5);
        assertNotNull("count increased → confirm_ts set", ts);
        assertTrue("confirm_ts is plausible epoch seconds", ts > 1_600_000_000L);
    }

    @Test
    public void confirmTsStickyAfterIncrease() {
        WazeConfirmTracker t = new WazeConfirmTracker();
        t.confirmTsSeconds("a", 1);
        Long first = t.confirmTsSeconds("a", 2);
        Long again = t.confirmTsSeconds("a", 2);   // no further increase
        assertNotNull(first);
        assertNotNull("keeps the last confirm time", again);
    }

    @Test
    public void nullThumbsTreatedAsZero() {
        WazeConfirmTracker t = new WazeConfirmTracker();
        assertNull(t.confirmTsSeconds("a", null));
        assertNull("still zero, no increase", t.confirmTsSeconds("a", null));
        assertNotNull("0 → 2 is an increase", t.confirmTsSeconds("a", 2));
    }
}
