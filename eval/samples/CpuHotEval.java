import java.lang.management.ManagementFactory;

/**
 * CPU-bound eval sample: the main thread spins forever inside a single obvious
 * hot method, {@link #hotChecksumLoop(byte[])}. A CPU profile should attribute
 * nearly all samples to that method.
 *
 * Expected diagnosis: hotChecksumLoop is the CPU hotspot.
 */
public class CpuHotEval {

    public static void main(String[] args) {
        long pid = ProcessHandle.current().pid();
        System.out.println("CpuHotEval PID=" + pid
                + " (jvm=" + ManagementFactory.getRuntimeMXBean().getName() + ")");
        System.out.flush();

        // Deterministic input the hot method chews on repeatedly.
        byte[] data = new byte[64 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }

        // Consume the result so the JIT cannot optimise the loop away.
        long sink = 0;
        while (true) {
            sink += hotChecksumLoop(data);
            if (sink == Long.MIN_VALUE) {
                // Unreachable in practice; defeats dead-code elimination.
                System.out.println(sink);
            }
        }
    }

    /**
     * Repeated CRC-style checksum arithmetic over the whole array. Pure CPU
     * work, no allocation, no I/O -- the single hotspot for this sample.
     */
    private static long hotChecksumLoop(byte[] data) {
        long crc = 0xFFFFFFFFL;
        for (int pass = 0; pass < 256; pass++) {
            for (byte datum : data) {
                crc ^= (datum & 0xFF);
                for (int bit = 0; bit < 8; bit++) {
                    long mask = -(crc & 1L);
                    crc = (crc >>> 1) ^ (0xEDB88320L & mask);
                }
            }
        }
        return crc;
    }
}
