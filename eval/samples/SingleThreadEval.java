import java.lang.management.ManagementFactory;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-thread-bottleneck eval sample: a pool of eight worker threads sits
 * idle (parked, doing nothing), while one thread named "pipeline-worker" does
 * all the CPU work serially in {@link #processSerially()}. A profile should
 * show CPU concentrated on the single pipeline-worker thread while the pool is
 * idle -- the work is not parallelised.
 *
 * Expected diagnosis: single-thread bottleneck on pipeline-worker.
 */
public class SingleThreadEval {

    private static final int POOL_SIZE = 8;

    public static void main(String[] args) throws InterruptedException {
        long pid = ProcessHandle.current().pid();
        System.out.println("SingleThreadEval PID=" + pid
                + " (jvm=" + ManagementFactory.getRuntimeMXBean().getName() + ")");
        System.out.flush();

        // A pool of eight threads that never receives any work -- pure idle.
        AtomicInteger idx = new AtomicInteger();
        ThreadFactory idleFactory = r -> {
            Thread t = new Thread(r, "idle-pool-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        // Prestart all core threads: a fixed pool spawns workers lazily, so without this the
        // idle-pool-* threads would never exist and the scenario would lose its point
        // (available-but-unused parallelism).
        ThreadPoolExecutor idlePool = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), idleFactory);
        idlePool.prestartAllCoreThreads();

        // All real work runs serially on this one thread.
        Thread worker = new Thread(SingleThreadEval::processSerially, "pipeline-worker");
        worker.setDaemon(false);
        worker.start();
        worker.join();
    }

    /** Runs the entire workload serially on the single pipeline-worker thread. */
    private static void processSerially() {
        long acc = 0;
        while (true) {
            for (int i = 0; i < 1_000_000; i++) {
                acc = (acc * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L);
                acc ^= (acc >>> 29);
            }
            if (acc == Long.MIN_VALUE) {
                // Unreachable; prevents dead-code elimination.
                System.out.println(acc);
            }
        }
    }
}
