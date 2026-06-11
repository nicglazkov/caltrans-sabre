package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Removals arrive as "RmAlert,&lt;uuid&gt;" old_command lines, not a protobuf message. */
public class WazeRtCodecTest {

    @Test
    public void parsesRmAlertRemovalsAndIgnoresOthers() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder().setOldCommand("RmAlert,uuid-1").build())
                .addElement(WazeProto.Element.newBuilder().setOldCommand("RmAlert, uuid-2 ").build())
                .addElement(WazeProto.Element.newBuilder().setOldCommand("SomeOtherCmd,x").build())
                .addElement(WazeProto.Element.newBuilder().build())   // no old_command
                .build();
        List<String> removed = WazeRtCodec.parseRemovedAlertIds(batch);
        assertEquals(Arrays.asList("uuid-1", "uuid-2"), removed);
    }

    @Test
    public void emptyBatchHasNoRemovals() {
        assertTrue(WazeRtCodec.parseRemovedAlertIds(
                WazeProto.Batch.newBuilder().build()).isEmpty());
    }
}
