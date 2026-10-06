package duke.kernel;

import duke.boot.Limine;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.KernelStacks;
import duke.kernel.time.HpetClock;
import duke.kernel.x86.Gdt;
import duke.kernel.x86.Idt;
import duke.kernel.x86.LocalApic;
import duke.rt.Magic;

/**
 * Brings up the other CPUs. Limine starts them and parks each on its limine_mp_info, in
 * bootloader-reclaimable memory, on its own page tables. The boot CPU gives each one a block (its
 * GS), a stack, and a GDT and TSS, then sends it to Magic.apEntry, which moves it onto our page
 * tables and stack before any Java runs. {@link #start} must finish before that memory is
 * reclaimed. Each CPU then loads its tables, enables its local APIC, checks in, and halts: running
 * threads on it is #93.
 */
public final class Smp {

    public static final int MAX_CPUS = 64;
    private static final int BLOCK_LONGS = 8;
    private static final int STACK_BASE = 24;
    private static final int INDEX = 32;
    private static final int STACK_TOP = 40;
    private static final int CR3 = 48;
    private static final int GDT = 56;
    private static final long CHECK_IN_NANOS = 1_000_000_000L;

    /** Blocks for CPUs other than the boot CPU, whose block the compiler emits. Image data, so Limine's page tables map it too. */
    private static final long[] BLOCKS = new long[MAX_CPUS * BLOCK_LONGS];
    private static final boolean[] ONLINE = new boolean[MAX_CPUS];
    private static final int[] APIC_IDS = new int[MAX_CPUS];
    private static int cpus = 1;

    private Smp() {
    }

    /** Starts every CPU Limine parked and waits for each to check in. Interrupts must be off. */
    public static void start() {
        long response = Limine.mpResponse();
        APIC_IDS[0] = LocalApic.id();
        ONLINE[0] = true;
        if (response == 0) {
            return;
        }
        int bspApic = Magic.peekInt(response + 12);
        long count = Magic.peekLong(response + 16);
        long infos = Magic.peekLong(response + 24);
        for (long i = 0; i < count && cpus < MAX_CPUS; i++) {
            long info = Magic.peekLong(infos + 8 * i);
            int apic = Magic.peekInt(info + 4);
            if (apic == bspApic) {
                continue;
            }
            int index = cpus++;
            long stack = KernelStacks.allocate();
            if (stack == -1) {
                Panic.panic("no stack for cpu ", Integer.toString(index));
            }
            long block = Magic.addressOf(BLOCKS) + 16 + 8L * BLOCK_LONGS * index;
            Magic.pokeLong(block, block);
            Magic.pokeLong(block + STACK_BASE, stack);
            Magic.pokeLong(block + INDEX, index);
            Magic.pokeLong(block + STACK_TOP, stack + KernelStacks.STACK_BYTES);
            Magic.pokeLong(block + CR3, KernelAddressSpace.table().root());
            Magic.pokeLong(block + GDT, Gdt.prepare(index));
            APIC_IDS[index] = apic;
            // Limine's spec: the extra argument first, then the goto address, which releases the CPU.
            Magic.pokeLong(info + 24, block);
            Magic.pokeLong(info + 16, Magic.apEntry());
        }
        long deadline = HpetClock.nanos() + CHECK_IN_NANOS;
        while (!allOnline() && HpetClock.nanos() < deadline) {
            Magic.pause();
        }
    }

    /** Where Magic.apEntry lands, on the new CPU, with GS at its block and interrupts off. */
    static void apMain() {
        Magic.resetStackLimit();
        long block = Magic.cpuBlock();
        Gdt.install(Magic.peekLong(block + GDT));
        Idt.loadOnThisCpu();
        LocalApic.enableOnThisCpu();
        ONLINE[(int) Magic.peekLong(block + INDEX)] = true;
        while (true) {
            Magic.disableInterrupts();
            Magic.halt();
        }
    }

    public static int cpuCount() {
        return cpus;
    }

    public static boolean online(int cpu) {
        return ONLINE[cpu];
    }

    public static int apicId(int cpu) {
        return APIC_IDS[cpu];
    }

    public static int onlineCount() {
        int online = 0;
        for (int i = 0; i < cpus; i++) {
            if (ONLINE[i]) {
                online++;
            }
        }
        return online;
    }

    private static boolean allOnline() {
        return onlineCount() == cpus;
    }
}
