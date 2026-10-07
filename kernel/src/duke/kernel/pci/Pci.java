package duke.kernel.pci;

import duke.kernel.acpi.Acpi;
import duke.kernel.mm.KernelAddressSpace;
import duke.rt.Magic;
import java.util.ArrayList;
import java.util.List;

/**
 * PCI devices found through ECAM, the memory-mapped configuration space the MCFG table describes:
 * each function's 4 KiB of config space sits at {@code base + (bus << 20 | device << 15 | function << 12)}.
 * The scan starts at the host bridges and follows PCI-to-PCI bridges, so only buses that exist get mapped.
 */
public final class Pci {

    public static final class Bar {
        public final int index;
        public final long address;
        public final boolean io;
        public final boolean wide;
        public final boolean prefetchable;

        Bar(int index, long address, boolean io, boolean wide, boolean prefetchable) {
            this.index = index;
            this.address = address;
            this.io = io;
            this.wide = wide;
            this.prefetchable = prefetchable;
        }
    }

    public static final class Function {
        public final int segment;
        public final int bus;
        public final int device;
        public final int function;
        /** Virtual address of this function's config space. */
        public final long config;
        public final int vendorId;
        public final int deviceId;
        public final int classCode;
        public final int subclass;
        public final int progIf;
        public final int revision;
        public final int headerType;
        public final List<Bar> bars = new ArrayList<>();

        Function(int segment, int bus, int device, int function, long config) {
            this.segment = segment;
            this.bus = bus;
            this.device = device;
            this.function = function;
            this.config = config;
            int id = Magic.peekInt(config);
            vendorId = id & 0xFFFF;
            deviceId = id >>> 16;
            int classes = Magic.peekInt(config + 8);
            revision = classes & 0xFF;
            progIf = classes >>> 8 & 0xFF;
            subclass = classes >>> 16 & 0xFF;
            classCode = classes >>> 24;
            headerType = Magic.peekByte(config + 14) & 0x7F;
            readBars(headerType == HEADER_GENERAL ? 6 : headerType == HEADER_BRIDGE ? 2 : 0);
        }

        private void readBars(int count) {
            for (int i = 0; i < count; i++) {
                int low = Magic.peekInt(config + 0x10 + 4 * i);
                if ((low & 1) != 0) {
                    if ((low & ~3) != 0) {
                        bars.add(new Bar(i, low & ~3, true, false, false));
                    }
                    continue;
                }
                boolean wide = (low >> 1 & 3) == 2;
                long address = low & ~0xFL & 0xFFFF_FFFFL;
                if (wide && i + 1 < count) {
                    address |= (long) Magic.peekInt(config + 0x10 + 4 * (i + 1)) << 32;
                }
                if (address != 0) {
                    bars.add(new Bar(i, address, false, wide, (low & 8) != 0));
                }
                if (wide) {
                    i++;
                }
            }
        }

        /** {@code 00:1f.2}, with the segment in front when it isn't 0. */
        public String address() {
            String bdf = hex(bus, 2) + ":" + hex(device, 2) + "." + function;
            return segment == 0 ? bdf : hex(segment, 4) + ":" + bdf;
        }

        /** {@code 00:1f.2 8086:2922 class 01.06.01 bar4 io 0xc040 bar5 mem 0xfebd5000}, like lspci -n. */
        public String describe() {
            StringBuilder sb = new StringBuilder(address()).append(' ').append(hex(vendorId, 4)).append(':').append(hex(deviceId, 4))
                    .append(" class ").append(hex(classCode, 2)).append('.').append(hex(subclass, 2))
                    .append('.').append(hex(progIf, 2));
            for (Bar bar : bars) {
                sb.append(" bar").append(bar.index).append(bar.io ? " io 0x" : " mem 0x").append(Long.toHexString(bar.address));
            }
            return sb.toString();
        }
    }

    private static final int HEADER_GENERAL = 0;
    private static final int HEADER_BRIDGE = 1;
    private static final int MCFG_ENTRIES = 44;
    private static final int MCFG_ENTRY_LENGTH = 16;

    private static final List<Function> functions = new ArrayList<>();
    private static long ecamBase;

    private Pci() {
    }

    public static void init() {
        long mcfg = Acpi.find("MCFG");
        if (mcfg == 0) {
            return;
        }
        int length = Magic.peekInt(mcfg + 4);
        for (long entry = mcfg + MCFG_ENTRIES; entry + MCFG_ENTRY_LENGTH <= mcfg + length; entry += MCFG_ENTRY_LENGTH) {
            long base = Magic.peekLong(entry);
            int segment = Magic.peekShort(entry + 8) & 0xFFFF;
            int startBus = Magic.peekByte(entry + 10) & 0xFF;
            int endBus = Magic.peekByte(entry + 11) & 0xFF;
            if (ecamBase == 0) {
                ecamBase = base;
            }
            new Scan(base, segment, startBus, endBus).hostBridges();
        }
    }

    /** Every function found, in the order the scan reached them. */
    public static List<Function> functions() {
        return functions;
    }

    /** Physical address of the first ECAM window, or 0 without an MCFG table. */
    public static long ecamBase() {
        return ecamBase;
    }

    /** The first function with this class and subclass, or null. */
    public static Function find(int classCode, int subclass) {
        for (Function f : functions) {
            if (f.classCode == classCode && f.subclass == subclass) {
                return f;
            }
        }
        return null;
    }

    private static final class Scan {
        private final long base;
        private final int segment;
        private final int startBus;
        private final int endBus;
        private final long[] mapped = new long[256];
        private final boolean[] scanned = new boolean[256];

        Scan(long base, int segment, int startBus, int endBus) {
            this.base = base;
            this.segment = segment;
            this.startBus = startBus;
            this.endBus = endBus;
        }

        /** A multi-function host bridge at 00.0 means function n is the host bridge for bus n. */
        void hostBridges() {
            long root = config(startBus, 0, 0);
            if ((Magic.peekByte(root + 14) & 0x80) == 0) {
                bus(startBus);
                return;
            }
            for (int f = 0; f < 8; f++) {
                if (present(config(startBus, 0, f))) {
                    bus(startBus + f);
                }
            }
        }

        private void bus(int bus) {
            if (bus < startBus || bus > endBus || scanned[bus]) {
                return;
            }
            scanned[bus] = true;
            for (int d = 0; d < 32; d++) {
                long first = config(bus, d, 0);
                if (!present(first)) {
                    continue;
                }
                int count = (Magic.peekByte(first + 14) & 0x80) != 0 ? 8 : 1;
                for (int f = 0; f < count; f++) {
                    long config = config(bus, d, f);
                    if (!present(config)) {
                        continue;
                    }
                    Function function = new Function(segment, bus, d, f, config);
                    functions.add(function);
                    if (function.headerType == HEADER_BRIDGE) {
                        bus(Magic.peekByte(config + 0x19) & 0xFF);
                    }
                }
            }
        }

        private long config(int bus, int device, int function) {
            if (mapped[bus] == 0) {
                mapped[bus] = KernelAddressSpace.mapDevice(base + ((long) (bus - startBus) << 20), 1 << 20);
            }
            return mapped[bus] + ((long) device << 15 | (long) function << 12);
        }

        private static boolean present(long config) {
            return (Magic.peekInt(config) & 0xFFFF) != 0xFFFF;
        }
    }

    static String hex(int value, int digits) {
        String s = Integer.toHexString(value);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < digits; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }
}
