package duke.conformance;

final class Threads {

    private static volatile boolean go;

    static long workersSplitASumAndJoin() throws InterruptedException {
        long[] partial = new long[4];
        Thread[] workers = new Thread[partial.length];
        for (int w = 0; w < workers.length; w++) {
            int index = w;
            workers[w] = new Thread(() -> {
                for (long i = index * 250_000L; i < (index + 1) * 250_000L; i++) {
                    partial[index] += i * i % 7;
                }
            }, "worker-" + w);
            workers[w].start();
        }
        long total = 0;
        for (int w = 0; w < workers.length; w++) {
            workers[w].join();
            total += partial[w];
        }
        return total;
    }

    static String currentThreadNames() throws InterruptedException {
        String[] inside = new String[1];
        Thread named = new Thread(() -> inside[0] = Thread.currentThread().getName(), "named");
        named.start();
        named.join();
        return Thread.currentThread().getName() + " " + inside[0] + " " + named.getName();
    }

    // The worker spins until main lets it go, so isAlive can't race its exit.
    static String isAliveAcrossALifetime() throws InterruptedException {
        go = false;
        Thread worker = new Thread(() -> {
            while (!go) {
            }
        }, "spinner");
        boolean beforeStart = worker.isAlive();
        worker.start();
        boolean running = worker.isAlive();
        go = true;
        worker.join();
        return beforeStart + " " + running + " " + worker.isAlive();
    }

    static String misuseThrows() throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        Thread once = new Thread(() -> { }, "once");
        once.start();
        once.join();
        try {
            once.start();
        } catch (IllegalThreadStateException e) {
            sb.append(e).append(';');
        }
        try {
            Thread.sleep(-1);
        } catch (IllegalArgumentException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new Thread(() -> { }, null);
        } catch (NullPointerException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static class Counter extends Thread {
        int count;

        Counter() {
            super("counter");
        }

        @Override
        public void run() {
            for (int i = 0; i < 1000; i++) {
                count++;
                if (i % 100 == 0) {
                    Thread.yield();
                }
            }
        }
    }

    static int subclassOverridesRun() throws InterruptedException {
        Counter counter = new Counter();
        counter.start();
        counter.join();
        return counter.count;
    }

    static int sleepingThreadsAllWake() throws InterruptedException {
        int[] woke = new int[5];
        Thread[] sleepers = new Thread[woke.length];
        for (int i = 0; i < sleepers.length; i++) {
            int index = i;
            sleepers[i] = new Thread(() -> {
                try {
                    Thread.sleep(10 * index);
                } catch (InterruptedException e) {
                    return;
                }
                woke[index] = index + 1;
            }, "sleeper-" + i);
            sleepers[i].start();
        }
        int sum = 0;
        for (int i = 0; i < sleepers.length; i++) {
            sleepers[i].join();
            sum += woke[i];
        }
        return sum;
    }

    static int threadsAllocateWhileOthersHoldReferences() throws InterruptedException {
        int[] lengths = new int[3];
        Thread[] builders = new Thread[lengths.length];
        for (int b = 0; b < builders.length; b++) {
            int index = b;
            builders[b] = new Thread(() -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 200; i++) {
                    sb.append(Integer.toString(i * (index + 1)));
                    if (i % 20 == 0) {
                        Thread.yield();
                    }
                }
                lengths[index] = sb.toString().length();
            }, "builder-" + b);
            builders[b].start();
        }
        int total = 0;
        for (int b = 0; b < builders.length; b++) {
            builders[b].join();
            total = total * 1000 + lengths[b];
        }
        return total;
    }
}
