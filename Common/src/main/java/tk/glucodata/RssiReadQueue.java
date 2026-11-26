package tk.glucodata;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serializes optional RSSI samples across GATT clients. Android shares RSSI callback routing. */
final class RssiReadQueue<G> {
    private record Read<G>(G gatt, BooleanSupplier start, Runnable onTimeout) {}

    private final Executor worker;
    private final Consumer<Runnable> postTimeout;
    private final ArrayDeque<Read<G>> pending = new ArrayDeque<>();
    private Read<G> active;
    private boolean acceptingResult;

    RssiReadQueue(Executor worker, Consumer<Runnable> postTimeout) {
        this.worker = worker;
        this.postTimeout = postTimeout;
    }

    synchronized void request(G gatt, BooleanSupplier start, Runnable onTimeout) {
        if (active != null && active.gatt() == gatt) return;
        for (Read<G> read : pending) {
            if (read.gatt() == gatt) return;
        }
        pending.addLast(new Read<>(gatt, start, onTimeout));
        dispatchNext();
    }

    synchronized void onResult(G gatt) {
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
                if (finish(read)) read.onTimeout().run();
            });
        } else {
            finish(read);
        }
    }

    private synchronized boolean finish(Read<G> read) {
        if (active != read) return false;
        active = null;
        acceptingResult = false;
        dispatchNext();
        return true;
    }
}
