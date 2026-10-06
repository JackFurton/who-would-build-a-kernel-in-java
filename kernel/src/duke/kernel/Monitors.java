package duke.kernel;

import duke.kernel.Scheduler.Task;
import duke.kernel.time.Timer;
import duke.rt.Magic;

/**
 * Java monitors: synchronized, wait and notify. A monitor exists only while some thread holds it,
 * waits on it or is blocked on it, as an entry in a small table keyed by the object's address,
 * which the non-moving collector keeps stable. Every thread involved has the object in one of its
 * frames, so the object outlives the entry. Releasing a monitor hands it straight to the first
 * blocked thread, so a releasing thread can't barge back in ahead of it.
 *
 * <p>Like the scheduler, all of this runs with interrupts off.
 */
public final class Monitors {

    static final class Monitor {
        long object;
        Task owner;
        int count;
        Task blockedHead;
        Task blockedTail;
        Task waitingHead;
        Task waitingTail;
        Monitor nextFree;
    }

    private static Monitor[] active;
    private static int activeCount;
    private static Monitor free;

    private Monitors() {
    }

    public static void enter(Object object) {
        Task self = Scheduler.current();
        if (self == null) {
            return;
        }
        long flags = Magic.flags();
        Magic.disableInterrupts();
        long address = Magic.addressOf(object);
        Monitor monitor = find(address);
        if (monitor == null) {
            monitor = open(address);
        }
        if (monitor.owner == null) {
            monitor.owner = self;
            monitor.count = 1;
        } else if (monitor.owner == self) {
            monitor.count++;
        } else {
            self.monitor = monitor;
            self.monitorCount = 1;
            self.state = Scheduler.BLOCKED;
            monitor.blockedTail = append(monitor.blockedHead, monitor.blockedTail, self);
            if (monitor.blockedHead == null) {
                monitor.blockedHead = self;
            }
            // Comes back owning the monitor: release() hands it over.
            Scheduler.switchTo(Scheduler.next());
        }
        Scheduler.restore(flags);
    }

    public static void exit(Object object) {
        if (!tryExit(object)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
    }

    /** For the unwinder leaving a synchronized method, where the monitor may already be gone. */
    public static void exitUnwinding(Object object) {
        tryExit(object);
    }

    public static void await(Object object, long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        Task self = Scheduler.current();
        long flags = Magic.flags();
        Magic.disableInterrupts();
        Monitor monitor = owned(object, self);
        if (monitor == null) {
            Scheduler.restore(flags);
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        self.monitor = monitor;
        self.monitorCount = monitor.count;
        self.state = Scheduler.WAITING;
        self.wakeTick = millis == 0 ? 0 : Timer.ticks() + (millis * Timer.HZ + 999) / 1000;
        monitor.waitingTail = append(monitor.waitingHead, monitor.waitingTail, self);
        if (monitor.waitingHead == null) {
            monitor.waitingHead = self;
        }
        release(monitor);
        Scheduler.switchTo(Scheduler.next());
        Scheduler.restore(flags);
    }

    public static void notify(Object object, boolean all) {
        Task self = Scheduler.current();
        long flags = Magic.flags();
        Magic.disableInterrupts();
        Monitor monitor = owned(object, self);
        if (monitor == null) {
            Scheduler.restore(flags);
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        do {
            Task waiter = monitor.waitingHead;
            if (waiter == null) {
                break;
            }
            unlinkWaiting(monitor, waiter);
            block(monitor, waiter);
        } while (all);
        Scheduler.restore(flags);
    }

    public static boolean holds(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
        long flags = Magic.flags();
        Magic.disableInterrupts();
        boolean held = owned(object, Scheduler.current()) != null;
        Scheduler.restore(flags);
        return held;
    }

    /** A timed wait ran out: from the scheduler, with interrupts off. */
    static void timedOut(Task task) {
        Monitor monitor = task.monitor;
        unlinkWaiting(monitor, task);
        if (monitor.owner == null) {
            monitor.owner = task;
            monitor.count = task.monitorCount;
            task.monitor = null;
            Scheduler.enqueue(task);
        } else {
            block(monitor, task);
        }
    }

    private static boolean tryExit(Object object) {
        Task self = Scheduler.current();
        if (self == null) {
            return true;
        }
        long flags = Magic.flags();
        Magic.disableInterrupts();
        Monitor monitor = owned(object, self);
        if (monitor != null && --monitor.count == 0) {
            release(monitor);
        }
        Scheduler.restore(flags);
        return monitor != null;
    }

    private static Monitor owned(Object object, Task self) {
        Monitor monitor = find(Magic.addressOf(object));
        return monitor != null && monitor.owner == self ? monitor : null;
    }

    /** Hands the monitor to the first blocked thread, or frees it. */
    private static void release(Monitor monitor) {
        Task next = monitor.blockedHead;
        if (next == null) {
            monitor.owner = null;
            if (monitor.waitingHead == null) {
                close(monitor);
            }
            return;
        }
        monitor.blockedHead = next.next;
        if (monitor.blockedHead == null) {
            monitor.blockedTail = null;
        }
        monitor.owner = next;
        monitor.count = next.monitorCount;
        next.monitor = null;
        Scheduler.enqueue(next);
    }

    private static void block(Monitor monitor, Task task) {
        task.state = Scheduler.BLOCKED;
        task.wakeTick = 0;
        monitor.blockedTail = append(monitor.blockedHead, monitor.blockedTail, task);
        if (monitor.blockedHead == null) {
            monitor.blockedHead = task;
        }
    }

    /** Appends to a list linked through Task.next and returns the new tail. */
    private static Task append(Task head, Task tail, Task task) {
        task.next = null;
        if (head != null) {
            tail.next = task;
        }
        return task;
    }

    private static void unlinkWaiting(Monitor monitor, Task task) {
        Task previous = null;
        for (Task t = monitor.waitingHead; t != null; previous = t, t = t.next) {
            if (t == task) {
                if (previous == null) {
                    monitor.waitingHead = t.next;
                } else {
                    previous.next = t.next;
                }
                if (monitor.waitingTail == t) {
                    monitor.waitingTail = previous;
                }
                t.next = null;
                return;
            }
        }
    }

    private static Monitor find(long address) {
        for (int i = 0; i < activeCount; i++) {
            if (active[i].object == address) {
                return active[i];
            }
        }
        return null;
    }

    private static Monitor open(long address) {
        Monitor monitor = free;
        if (monitor != null) {
            free = monitor.nextFree;
            monitor.nextFree = null;
        } else {
            monitor = new Monitor();
        }
        monitor.object = address;
        if (active == null) {
            active = new Monitor[16];
        } else if (activeCount == active.length) {
            Monitor[] grown = new Monitor[active.length * 2];
            for (int i = 0; i < activeCount; i++) {
                grown[i] = active[i];
            }
            active = grown;
        }
        active[activeCount++] = monitor;
        return monitor;
    }

    private static void close(Monitor monitor) {
        for (int i = 0; i < activeCount; i++) {
            if (active[i] == monitor) {
                active[i] = active[--activeCount];
                active[activeCount] = null;
                break;
            }
        }
        monitor.object = 0;
        monitor.nextFree = free;
        free = monitor;
    }
}
