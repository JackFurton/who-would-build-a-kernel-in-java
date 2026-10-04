package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.mm.PhysicalMemory;
import duke.rt.Heap;

final class CollectorTest {

    static final class Node {
        final int value;
        final Node next;
        final byte[] payload;

        Node(int value, Node next) {
            this.value = value;
            this.next = next;
            this.payload = new byte[32];
            payload[31] = (byte) value;
        }
    }

    static Node kept;

    private static void churn(long bytes) {
        for (long done = 0; done < bytes; done += 1 << 20) {
            byte[] garbage = new byte[1 << 20];
            garbage[0] = 1;
        }
    }

    private static int sum(Node head) {
        int total = 0;
        for (Node n = head; n != null; n = n.next) {
            if (n.payload[31] != (byte) n.value) {
                throw new AssertionError("payload of node " + n.value + " corrupted");
            }
            total += n.value;
        }
        return total;
    }

    // Far more than physical memory (188 MiB under QEMU): only works if garbage is reclaimed.
    static void testGarbageIsReclaimed() {
        long framesBefore = PhysicalMemory.freeFrames();
        churn(1L << 30);
        assertTrue(Heap.committed() < 128L << 20, "committed heap stays bounded: " + (Heap.committed() >> 20) + " MiB");
        assertTrue(PhysicalMemory.freeFrames() > framesBefore - (128L << 20) / 4096, "frames not leaked");
    }

    static void testLiveDataSurvivesCollections() {
        Node head = null;
        for (int i = 1; i <= 20_000; i++) {
            head = new Node(i, head);
        }
        kept = new Node(-1, null);
        churn(256L << 20);
        System.gc();
        assertEquals(20_000L * 20_001 / 2, sum(head), "list from a local");
        assertEquals(-1, kept.value, "object from a static");
        assertEquals((byte) -1, kept.payload[31], "its payload");
    }

    private static int combine(Node a, int garbageAllocated, Node b) {
        return a.value + b.value + garbageAllocated;
    }

    private static int allocateWhileArgumentsArePending() {
        churn(64L << 20);
        System.gc();
        return 0;
    }

    // The first argument sits only on the caller's operand stack while the second is computed.
    static void testOperandStackRootsSurvive() {
        int result = combine(new Node(40, null), allocateWhileArgumentsArePending(), new Node(2, null));
        assertEquals(42, result, "both nodes intact");
    }

    static void testIdentityHashIsStableAcrossCollections() {
        Object o = new Object();
        int before = o.hashCode();
        churn(64L << 20);
        System.gc();
        assertEquals(before, o.hashCode(), "hash");
    }

    static void testHolesAreReused() {
        Object[] survivors = new Object[1000];
        for (int i = 0; i < 100_000; i++) {
            Object o = new int[i % 50];
            if (i % 100 == 0) {
                survivors[i / 100] = o;
            }
        }
        System.gc();
        long committed = Heap.committed();
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 50_000; i++) {
                Object o = new int[i % 50];
            }
        }
        assertEquals(committed, Heap.committed(), "freed space is reused before committing more");
        for (int i = 0; i < survivors.length; i++) {
            assertEquals(((i * 100) % 50), ((int[]) survivors[i]).length, "survivor " + i);
        }
    }

    // Collect at every allocation while ordinary code runs: any slot missing from a stack map
    // shows up as corrupted data or a GC panic.
    static void testStressModeKeepsEverythingIntact() {
        Heap.stress(1);
        try {
            StringBuilder sb = new StringBuilder();
            Node head = null;
            for (int i = 0; i < 300; i++) {
                head = new Node(i, head);
                sb.append(i).append(',');
                String s = "x" + i + head.value;
                assertEquals("x" + i + i, s, "concat " + i);
            }
            assertEquals(299 * 300 / 2, sum(head), "list");
            assertTrue(sb.toString().startsWith("0,1,2,"), "builder");
            try {
                Object[] objects = new String[1];
                objects[0] = new Object();
            } catch (ArrayStoreException e) {
                assertEquals("java.lang.Object", e.getMessage(), "exception built mid-stress");
            }
        } finally {
            Heap.stress(0);
        }
    }
}
