package tk.glucodata;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class NotificationPredictionBatchTests {
    private static List<GlucosePoint> history() {
        return new ArrayList<>(List.of(new GlucosePoint(60_000L, 110f)));
    }

    private static List<NotificationPredictionSeries> resolve(
            NotificationPredictionBatch batch, List<GlucosePoint> history,
            Supplier<List<NotificationPredictionSeries>> build) {
        return batch.resolve(history, false, 0, false, "sensor", 70f, 180f, build);
    }

    @Test public void bothLayoutsShareOnePredictionCalculation() {
        var batch = new NotificationPredictionBatch();
        var history = history();
        var calls = new AtomicInteger();
        Supplier<List<NotificationPredictionSeries>> build = () -> {
            calls.incrementAndGet();
            return new ArrayList<>();
        };
        var collapsed = resolve(batch, history, build);
        var expanded = resolve(batch, history, build);
        assertSame(collapsed, expanded);
        assertEquals(1, calls.get());
    }

    @Test public void nextNotificationRecalculatesEvenWithTheSameHistoryObject() {
        var history = history();
        var calls = new AtomicInteger();
        Supplier<List<NotificationPredictionSeries>> build = () -> {
            calls.incrementAndGet();
            return new ArrayList<>();
        };
        var first = resolve(new NotificationPredictionBatch(), history, build);
        history.get(0).value = 120f;
        var next = resolve(new NotificationPredictionBatch(), history, build);
        assertNotSame(first, next);
        assertEquals(2, calls.get());
    }

    @Test public void peerHistoriesStayIndependent() {
        var batch = new NotificationPredictionBatch();
        var primary = history();
        // The same point objects in a separate list must not become the primary's cache entry.
        var peer = new ArrayList<>(primary);
        var calls = new AtomicInteger();
        Supplier<List<NotificationPredictionSeries>> build = () -> {
            calls.incrementAndGet();
            return new ArrayList<>();
        };
        var primaryResult = resolve(batch, primary, build);
        var peerResult = resolve(batch, peer, build);
        assertNotSame(primaryResult, peerResult);
        assertSame(primaryResult, resolve(batch, primary, build));
        assertSame(peerResult, resolve(batch, peer, build));
        assertEquals(2, calls.get());
    }

    @Test public void changedPredictionInputsDoNotReuseResults() {
        var batch = new NotificationPredictionBatch();
        var history = history();
        var calls = new AtomicInteger();
        Supplier<List<NotificationPredictionSeries>> build = () -> {
            calls.incrementAndGet();
            return Collections.emptyList();
        };
        resolve(batch, history, build);
        batch.resolve(history, true, 0, false, "sensor", 70f, 180f, build);
        batch.resolve(history, false, 1, false, "sensor", 70f, 180f, build);
        batch.resolve(history, false, 0, true, "sensor", 70f, 180f, build);
        batch.resolve(history, false, 0, false, "peer", 70f, 180f, build);
        batch.resolve(history, false, 0, false, "sensor", 80f, 180f, build);
        batch.resolve(history, false, 0, false, "sensor", 70f, 170f, build);
        assertEquals(7, calls.get());
        resolve(batch, history, build);
        assertEquals(7, calls.get());
    }

    @Test public void disabledOrUnavailablePredictionsAreOnlyRequestedOnce() {
        var batch = new NotificationPredictionBatch();
        var history = history();
        var calls = new AtomicInteger();
        Supplier<List<NotificationPredictionSeries>> build = () -> {
            calls.incrementAndGet();
            return Collections.emptyList();
        };
        assertTrue(resolve(batch, history, build).isEmpty());
        assertTrue(resolve(batch, history, build).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test public void failedCalculationDoesNotPoisonTheBatch() {
        var batch = new NotificationPredictionBatch();
        var history = history();
        assertThrows(IllegalStateException.class, () -> resolve(batch, history, () -> {
            throw new IllegalStateException("calculation failed");
        }));
        var result = new ArrayList<NotificationPredictionSeries>();
        assertSame(result, resolve(batch, history, () -> result));
    }
}
