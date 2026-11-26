package tk.glucodata;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serializes optional RSSI samples across GATT clients. Android shares RSSI callback routing. */
final class RssiReadQueue<G> {
    private record Read<G>(G gatt, BooleanSupplier start, Runnable onTimeout) {}

    private final Executor worker;
    private final Consumer<Runnable> postTimeout;
    private final ArrayDeque<Read<G>> pending = new ArrayDeque<>();
    private final ArrayList<WeakReference<G>> awaitingLateResult = new ArrayList<>();
    private Read<G> active;
    private boolean acceptingResult;

    RssiReadQueue(Executor worker, Consumer<Runnable> postTimeout) {
        this.worker = worker;
        this.postTimeout = postTimeout;
    }

    synchronized void request(G gatt, BooleanSupplier start, Runnable onTimeout) {
        if (active != null && active.gatt() == gatt) return;
        awaitingLateResult.removeIf(link -> link.get() == null);
        for (WeakReference<G> link : awaitingLateResult) {
            if (link.get() == gatt) return;
        }
        for (Read<G> read : pending) {
            if (read.gatt() == gatt) return;
        }
        pending.addLast(new Read<>(gatt, start, onTimeout));
        dispatchNext();
    }

    synchronized void onResult(G gatt) {
        if (awaitingLateResult.removeIf(link -> link.get() == gatt)) return;
        if (acceptingResult && active.gatt() == gatt) finish(active);
    }

    private void dispatchNext() {
        if (active != null || pending.isEmpty()) return;
        Read<G> read = pending.removeFirst();
        active = read;
        worker.execute(() -> start(read));
    }

    private void start(Read<G> read) {
        synchronized (this) {
            acceptingResult = true;
        }
        if (read.start().getAsBoolean()) {
            postTimeout.accept(() -> {
                if (expire(read)) read.onTimeout().run();
            });
        } else {
            finish(read);
        }
    }

    private synchronized boolean expire(Read<G> read) {
        if (active != read) return false;
        // Android supplies only the GATT in its callback. Wait for the late result
        // before reusing that GATT and let replacement GATTs be collected normally.
        awaitingLateResult.add(new WeakReference<>(read.gatt()));
        return finish(read);
    }

    private synchronized boolean finish(Read<G> read) {
        if (active != read) return false;
        active = null;
        acceptingResult = false;
        dispatchNext();
        return true;
    }
}
