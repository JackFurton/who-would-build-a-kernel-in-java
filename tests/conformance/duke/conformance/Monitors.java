package duke.conformance;

final class Monitors {

    private static final Object LOCK = new Object();
    private static int shared;
    private static int staticCount;

    // Four threads, no yields: only the lock keeps the read-modify-write whole under preemption.
    static int synchronizedBlocksGiveAnExactCount() throws InterruptedException {
        shared = 0;
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 20_000; i++) {
                    synchronized (LOCK) {
                        int seen = shared;
                        spin();
                        shared = seen + 1;
                    }
                }
            }, "adder-" + t);
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return shared;
    }

    private static void spin() {
    }

    static final class Counter {
        private int count;

        synchronized void add() {
            int seen = count;
            spin();
            count = seen + 1;
        }

        synchronized int get() {
            return count;
        }
    }

    private static synchronized void addStatic() {
        int seen = staticCount;
        spin();
        staticCount = seen + 1;
    }

    static long synchronizedMethodsGiveAnExactCount() throws InterruptedException {
        Counter counter = new Counter();
        staticCount = 0;
        Thread[] threads = new Thread[3];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 20_000; i++) {
                    counter.add();
                    addStatic();
                }
            }, "method-adder-" + t);
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return counter.get() * 1_000_000L + staticCount;
    }

    static int monitorsAreReentrant() {
        int depth = 0;
        synchronized (LOCK) {
            depth++;
            synchronized (LOCK) {
                depth++;
                synchronized (LOCK) {
                    depth++;
                }
            }
        }
        return depth + reenterThroughMethods(new Counter());
    }

    private static int reenterThroughMethods(Counter counter) {
        synchronized (counter) {
            counter.add();
            counter.add();
            return counter.get();
        }
    }

    static final class Fragile {
        synchronized void fail() {
            throw new IllegalStateException("inside a synchronized method");
        }
    }

    // An exception out of a synchronized method or block must leave the monitor free for others.
    static String exceptionsReleaseMonitors() throws InterruptedException {
        Fragile fragile = new Fragile();
        StringBuilder sb = new StringBuilder();
        try {
            fragile.fail();
        } catch (IllegalStateException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            synchronized (LOCK) {
                throw new ArithmeticException("inside a synchronized block");
            }
        } catch (ArithmeticException e) {
            sb.append(e.getMessage()).append(';');
        }
        boolean[] acquired = new boolean[2];
        Thread other = new Thread(() -> {
            synchronized (fragile) {
                acquired[0] = true;
            }
            synchronized (LOCK) {
                acquired[1] = true;
            }
        }, "other");
        other.start();
        other.join();
        return sb.append(acquired[0]).append(' ').append(acquired[1]).toString();
    }

    static final class Channel {
        private final int[] buffer = new int[4];
        private int head;
        private int size;

        synchronized void put(int value) throws InterruptedException {
            while (size == buffer.length) {
                wait();
            }
            buffer[(head + size) % buffer.length] = value;
            size++;
            notifyAll();
        }

        synchronized int take() throws InterruptedException {
            while (size == 0) {
                wait();
            }
            int value = buffer[head];
            head = (head + 1) % buffer.length;
            size--;
            notifyAll();
            return value;
        }
    }

    static long producersAndConsumersThroughWaitAndNotify() throws InterruptedException {
        Channel channel = new Channel();
        long[] sums = new long[2];
        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 500; i++) {
                    channel.put(i);
                }
                channel.put(-1);
                channel.put(-1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }, "producer");
        Thread[] consumers = new Thread[2];
        for (int c = 0; c < consumers.length; c++) {
            int index = c;
            consumers[c] = new Thread(() -> {
                try {
                    for (int value = channel.take(); value != -1; value = channel.take()) {
                        sums[index] += value;
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }, "consumer-" + c);
            consumers[c].start();
        }
        producer.start();
        producer.join();
        for (Thread consumer : consumers) {
            consumer.join();
        }
        return sums[0] + sums[1];
    }

    static boolean timedWaitReturnsWithTheMonitorHeld() throws InterruptedException {
        synchronized (LOCK) {
            LOCK.wait(20);
            LOCK.notify();
            return Thread.holdsLock(LOCK);
        }
    }

    static String misuseThrows() {
        StringBuilder sb = new StringBuilder();
        Object free = new Object();
        try {
            free.notify();
        } catch (IllegalMonitorStateException e) {
            sb.append(e).append(';');
        }
        try {
            free.wait();
        } catch (IllegalMonitorStateException | InterruptedException e) {
            sb.append(e).append(';');
        }
        synchronized (free) {
            try {
                free.wait(-1);
            } catch (IllegalArgumentException | InterruptedException e) {
                sb.append(e.getMessage()).append(';');
            }
            try {
                free.wait(1, 1_000_000);
            } catch (IllegalArgumentException | InterruptedException e) {
                sb.append(e.getMessage()).append(';');
            }
        }
        return sb.append(Thread.holdsLock(free)).toString();
    }
}
