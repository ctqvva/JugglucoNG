package tk.glucodata;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RssiReadQueueTest {
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private final ArrayDeque<Runnable> timeouts = new ArrayDeque<>();
    private final List<String> events = new ArrayList<>();
    private final RssiReadQueue<Object> reads = new RssiReadQueue<>(work::addLast, timeouts::addLast);
    private final Object firstGatt = new Object();
    private final Object secondGatt = new Object();

    @Test
    public void pollsBothLinksInOrderAndCoalescesRepeatedRequests() {
        request(firstGatt, "first", true);
        request(firstGatt, "duplicate active", true);
        request(secondGatt, "second", true);
        request(secondGatt, "duplicate pending", true);

        runWork();
        assertEquals(List.of("start first"), events);
        reads.onResult(firstGatt);
        runWork();
        assertEquals(List.of("start first", "start second"), events);
        reads.onResult(secondGatt);
        runWork();
        assertEquals(List.of("start first", "start second"), events);
    }

    @Test
    public void rejectedOrDisconnectedLinkLetsTheNextReadStartImmediately() {
        request(firstGatt, "first", false);
        request(secondGatt, "second", true);

        runWork();
        assertEquals(List.of("start first", "start second"), events);
        assertEquals(1, timeouts.size());
    }

    @Test
    public void timeoutUnblocksTheNextLinkAndOldGattResultCannotReleaseIt() {
        request(firstGatt, "first", true);
        request(secondGatt, "second", true);
        runWork();

        timeouts.removeFirst().run();
        runWork();
        reads.onResult(firstGatt);
        request(firstGatt, "retry first", true);
        runWork();
        assertEquals(List.of("start first", "timeout first", "start second"), events);

        reads.onResult(secondGatt);
        runWork();
        assertEquals(List.of("start first", "timeout first", "start second", "start retry first"), events);
    }

    @Test
    public void timedOutGattWaitsForItsCallbackWhileAnotherGattCanStart() {
        request(firstGatt, "first", true);
        runWork();
        timeouts.removeFirst().run();

        request(firstGatt, "premature retry", true);
        request(secondGatt, "replacement", true);
        runWork();
        assertEquals(List.of("start first", "timeout first", "start replacement"), events);

        reads.onResult(firstGatt);
        request(firstGatt, "retry after late result", true);
        runWork();
        assertEquals(List.of("start first", "timeout first", "start replacement"), events);

        reads.onResult(secondGatt);
        runWork();
        assertEquals(List.of("start first", "timeout first", "start replacement", "start retry after late result"), events);
    }

    @Test
    public void obsoleteTimeoutDoesNotReleaseANewerReadOnTheSameGatt() {
        request(firstGatt, "first", true);
        runWork();
        reads.onResult(firstGatt);
        request(firstGatt, "next", true);
        request(secondGatt, "second", true);
        runWork();

        timeouts.removeFirst().run();
        runWork();
        assertEquals(List.of("start first", "start next"), events);

        reads.onResult(firstGatt);
        runWork();
        assertEquals(List.of("start first", "start next", "start second"), events);
    }

    @Test
    public void resultBeforeDispatchCannotReleaseAReadThatHasNotStarted() {
        request(firstGatt, "first", true);
        request(secondGatt, "second", true);
        reads.onResult(firstGatt);
        runWork();
        assertEquals(List.of("start first"), events);
    }

    private void request(Object gatt, String label, boolean starts) {
        reads.request(gatt, () -> {
            events.add("start " + label);
            return starts;
        }, () -> events.add("timeout " + label));
    }

    private void runWork() {
        while (!work.isEmpty()) work.removeFirst().run();
    }
}
