import java.lang.management.ManagementFactory;

/**
 * Lock-contention eval sample: eight worker threads each loop forever, and on
 * every iteration they synchronize on one shared {@link SharedLedger} monitor
 * and hold the lock while doing ~5ms of CPU busy-work (no sleeping inside the
 * lock, so the monitor is genuinely contended). A profile should show heavy
 * blocking on the SharedLedger monitor via jdk.JavaMonitorEnter events.
 *
 * Expected diagnosis: contention on the SharedLedger monitor.
 */
public class LockContentionEval {

    private static final int WORKERS = 8;

    /** The single shared monitor every worker fights over. */
    static final class SharedLedger {
        private long balance;

        /** Holds the monitor while burning ~5ms of CPU -- the contention point. */
        synchronized void post(int amount) {
            long deadline = System.nanoTime() + 5_000_000L; // ~5ms of busy-work
            long acc = balance;
            while (System.nanoTime() < deadline) {
                acc = (acc * 1_000_003L + amount) ^ (acc >>> 7);
            }
            balance = acc;
        }
    }

    public static void main(String[] args) {
        long pid = ProcessHandle.current().pid();
        System.out.println("LockContentionEval PID=" + pid
                + " (jvm=" + ManagementFactory.getRuntimeMXBean().getName() + ")");
        System.out.flush();

        SharedLedger ledger = new SharedLedger();
        for (int i = 0; i < WORKERS; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                while (true) {
                    ledger.post(id);
                }
            }, "ledger-worker-" + i);
            t.setDaemon(true);
            t.start();
        }

        // Keep the main thread alive without competing for the monitor.
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
