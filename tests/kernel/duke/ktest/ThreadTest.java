package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Console;
import duke.kernel.mm.KernelStacks;
import duke.kernel.time.HpetClock;
import duke.rt.Heap;

final class ThreadTest {

    private static final char[] TRACE = new char[400];
    private static int traced;
    private static volatile boolean stop;
    private static volatile long spins;

    // Neither thread yields or sleeps: only the timer, or running on two CPUs, makes them take turns.
    static void testTwoThreadsInterleave() throws InterruptedException {
        traced = 0;
        Thread a = new Thread(() -> trace('a'), "a");
        Thread b = new Thread(() -> trace('b'), "b");
        a.start();
        b.start();
        a.join();
        b.join();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < traced; i++) {
            sb.append(TRACE[i]);
        }
        String trace = sb.toString();
        Console.println("");
        Console.println("  " + trace);
        int switches = 0;
        for (int i = 1; i < trace.length(); i++) {
            if (trace.charAt(i) != trace.charAt(i - 1)) {
                switches++;
            }
        }
        assertEquals(400, trace.length(), "both threads finished");
        assertTrue(switches >= 4, "threads took turns " + switches + " times");
    }

    private static void trace(char c) {
        for (int i = 0; i < 200; i++) {
            // Locked: on two CPUs at once, the increments would race.
            synchronized (TRACE) {
                TRACE[traced++] = c;
            }
            HpetClock.spinNanos(250_000);
        }
    }

    // A loop with no calls in it reaches no prologue, so only the back-edge poll can preempt it.
    static void testLoopWithoutCallsIsPreempted() throws InterruptedException {
        stop = false;
        spins = 0;
        Thread spinner = new Thread(() -> {
            while (!stop) {
                spins++;
            }
        });
        spinner.start();
        Thread.sleep(50);
        stop = true;
        spinner.join();
        assertTrue(spins > 0, "the spinner ran");
    }

    static void testSleepWaitsAndJoinWaitsForExit() throws InterruptedException {
        long[] woke = new long[1];
        long start = HpetClock.nanos();
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            woke[0] = HpetClock.nanos();
        });
        sleeper.start();
        assertTrue(sleeper.isAlive(), "alive after start");
        sleeper.join();
        assertTrue(!sleeper.isAlive(), "dead after join");
        long sleptMillis = (woke[0] - start) / 1_000_000;
        assertTrue(sleptMillis >= 90, "slept ~100 ms: " + sleptMillis);
    }

    static void testUncaughtExceptionEndsOnlyItsThread() throws InterruptedException {
        Thread doomed = new Thread(() -> {
            throw new IllegalStateException("on purpose");
        }, "doomed");
        doomed.start();
        doomed.join();
        assertTrue(!doomed.isAlive(), "the thread ended");
    }

    static void testCurrentThread() throws InterruptedException {
        assertEquals("main", Thread.currentThread().getName(), "the boot thread");
        String[] seen = new String[1];
        Thread worker = new Thread(() -> seen[0] = Thread.currentThread().getName(), "worker");
        worker.start();
        worker.join();
        assertEquals("worker", seen[0], "inside the thread");
    }

    static void testStacksAreReturned() throws InterruptedException {
        int before = KernelStacks.inUse();
        for (int i = 0; i < 100; i++) {
            Thread t = new Thread(() -> { });
            t.start();
            t.join();
        }
        assertEquals(before, KernelStacks.inUse(), "stacks in use after 100 threads came and went");
    }

    // Collections run while every other thread is parked holding references only in its frames.
    static void testCollectorScansParkedStacks() throws InterruptedException {
        long[] sums = new long[4];
        Thread[] threads = new Thread[sums.length];
        Heap.stress(7);
        for (int t = 0; t < threads.length; t++) {
            int index = t;
            threads[t] = new Thread(() -> sums[index] = buildAndSum(index));
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        Heap.stress(0);
        for (int t = 0; t < sums.length; t++) {
            assertEquals(expectedSum(t), sums[t], "thread " + t + "'s list survived");
        }
    }

    private static final class Node {
        final long value;
        final Node next;

        Node(long value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    private static long buildAndSum(int seed) {
        Node list = null;
        for (int i = 0; i < 300; i++) {
            list = new Node(seed * 1000L + i, list);
            if (i % 10 == 0) {
                Thread.yield();
            }
        }
        long sum = 0;
        for (Node n = list; n != null; n = n.next) {
            sum += n.value;
        }
        return sum;
    }

    private static long expectedSum(int seed) {
        return 300 * seed * 1000L + 299 * 300 / 2;
    }
}
