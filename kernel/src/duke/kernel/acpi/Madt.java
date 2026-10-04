package duke.kernel.acpi;

import duke.rt.Magic;
import java.util.ArrayList;
import java.util.List;

/** The MADT ("APIC" table): local APICs (one per CPU), I/O APICs and ISA interrupt overrides. */
public final class Madt {

    public static final class LocalApic {
        public final int processorId;
        public final int apicId;
        public final boolean enabled;

        LocalApic(int processorId, int apicId, boolean enabled) {
            this.processorId = processorId;
            this.apicId = apicId;
            this.enabled = enabled;
        }
    }

    public static final class IoApic {
        public final int id;
        public final long address;
        public final int gsiBase;

        IoApic(int id, long address, int gsiBase) {
            this.id = id;
            this.address = address;
            this.gsiBase = gsiBase;
        }
    }

    /** An ISA IRQ wired to a different global system interrupt, with its own polarity and trigger. */
    public static final class Override {
        public final int irq;
        public final int gsi;
        public final int flags;

        Override(int irq, int gsi, int flags) {
            this.irq = irq;
            this.gsi = gsi;
            this.flags = flags;
        }

        public boolean activeLow() {
            return (flags & 3) == 3;
        }

        public boolean levelTriggered() {
            return (flags >> 2 & 3) == 3;
        }
    }

    private static long localApicAddress;
    private static boolean legacyPics;
    private static final List<LocalApic> cpus = new ArrayList<>();
    private static final List<IoApic> ioApics = new ArrayList<>();
    private static final List<Override> overrides = new ArrayList<>();

    private Madt() {
    }

    public static void init() {
        long madt = Acpi.find("APIC");
        if (madt == 0) {
            throw new IllegalStateException("no MADT: this kernel needs an APIC");
        }
        localApicAddress = Magic.peekInt(madt + 36) & 0xFFFF_FFFFL;
        legacyPics = (Magic.peekInt(madt + 40) & 1) != 0;
        long end = madt + Acpi.length(madt);
        for (long entry = madt + 44; entry < end; ) {
            int type = Magic.peekByte(entry) & 0xFF;
            int length = Magic.peekByte(entry + 1) & 0xFF;
            switch (type) {
                case 0 -> cpus.add(new LocalApic(Magic.peekByte(entry + 2) & 0xFF, Magic.peekByte(entry + 3) & 0xFF,
                        (Magic.peekInt(entry + 4) & 1) != 0));
                case 1 -> ioApics.add(new IoApic(Magic.peekByte(entry + 2) & 0xFF, Magic.peekInt(entry + 4) & 0xFFFF_FFFFL,
                        Magic.peekInt(entry + 8)));
                case 2 -> overrides.add(new Override(Magic.peekByte(entry + 3) & 0xFF, Magic.peekInt(entry + 4),
                        Magic.peekShort(entry + 8) & 0xFFFF));
                case 5 -> localApicAddress = Magic.peekLong(entry + 4);
                default -> { }
            }
            if (length < 2) {
                break;
            }
            entry += length;
        }
    }

    public static long localApicAddress() {
        return localApicAddress;
    }

    /** Whether the dual 8259 PICs exist (and must be masked when the APIC takes over). */
    public static boolean legacyPics() {
        return legacyPics;
    }

    public static List<LocalApic> cpus() {
        return cpus;
    }

    public static List<IoApic> ioApics() {
        return ioApics;
    }

    public static List<Override> overrides() {
        return overrides;
    }

    /** The global system interrupt an ISA IRQ arrives on, after overrides. */
    public static int gsiForIrq(int irq) {
        for (Override o : overrides) {
            if (o.irq == irq) {
                return o.gsi;
            }
        }
        return irq;
    }
}
