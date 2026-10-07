package duke.kernel.virtio;

import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.pci.Pci;
import duke.rt.Magic;

/**
 * The virtio 1.0 "modern" PCI transport: vendor capabilities in config space point into the BARs
 * for the common configuration, the queue doorbells and the device's own configuration.
 */
final class VirtioPci {

    static final int VENDOR = 0x1af4;

    static final int ACKNOWLEDGE = 1;
    static final int DRIVER = 2;
    static final int DRIVER_OK = 4;
    static final int FEATURES_OK = 8;
    static final int FAILED = 128;

    private static final int CAPABILITY_VENDOR = 0x09;
    private static final int CONFIG_COMMON = 1;
    private static final int CONFIG_NOTIFY = 2;
    private static final int CONFIG_DEVICE = 4;

    // struct virtio_pci_common_cfg
    private static final int DEVICE_FEATURE_SELECT = 0x00;
    private static final int DEVICE_FEATURE = 0x04;
    private static final int DRIVER_FEATURE_SELECT = 0x08;
    private static final int DRIVER_FEATURE = 0x0C;
    private static final int DEVICE_STATUS = 0x14;
    private static final int QUEUE_SELECT = 0x16;
    private static final int QUEUE_SIZE = 0x18;
    private static final int QUEUE_ENABLE = 0x1C;
    private static final int QUEUE_NOTIFY_OFF = 0x1E;
    private static final int QUEUE_DESC = 0x20;
    private static final int QUEUE_DRIVER = 0x28;
    private static final int QUEUE_DEVICE = 0x30;

    private static final int COMMAND_MEMORY = 1 << 1;
    private static final int COMMAND_BUS_MASTER = 1 << 2;

    final Pci.Function function;
    private long common;
    private long notify;
    private int notifyMultiplier;
    private long device;

    VirtioPci(Pci.Function function) {
        this.function = function;
        long config = function.config;
        Magic.pokeShort(config + 4, (short) (Magic.peekShort(config + 4) | COMMAND_MEMORY | COMMAND_BUS_MASTER));
        int at = Magic.peekByte(config + 0x34) & 0xFC;
        while (at != 0) {
            long cap = config + at;
            if ((Magic.peekByte(cap) & 0xFF) == CAPABILITY_VENDOR) {
                int type = Magic.peekByte(cap + 3) & 0xFF;
                int bar = Magic.peekByte(cap + 4) & 0xFF;
                long offset = Magic.peekInt(cap + 8) & 0xFFFF_FFFFL;
                long length = Magic.peekInt(cap + 12) & 0xFFFF_FFFFL;
                // The first capability of each type is the one to use; later ones are alternatives.
                if (type == CONFIG_COMMON && common == 0) {
                    common = map(bar, offset, length);
                } else if (type == CONFIG_NOTIFY && notify == 0) {
                    notify = map(bar, offset, length);
                    notifyMultiplier = Magic.peekInt(cap + 16);
                } else if (type == CONFIG_DEVICE && device == 0) {
                    device = map(bar, offset, length);
                }
            }
            at = Magic.peekByte(cap + 1) & 0xFC;
        }
        if (common == 0 || notify == 0) {
            throw new IllegalStateException("virtio device at " + function.address() + " has no modern interface");
        }
    }

    private long map(int index, long offset, long length) {
        for (Pci.Bar bar : function.bars) {
            if (bar.index == index && !bar.io) {
                return KernelAddressSpace.mapDevice(bar.address + offset, length);
            }
        }
        throw new IllegalStateException("virtio capability in missing BAR " + index);
    }

    /** Virtual address of the device-specific configuration, or 0 without one. */
    long deviceConfig() {
        return device;
    }

    void reset() {
        Magic.pokeByte(common + DEVICE_STATUS, (byte) 0);
        while (status() != 0) {
            Magic.pause();
        }
    }

    int status() {
        return Magic.peekByte(common + DEVICE_STATUS) & 0xFF;
    }

    void addStatus(int bits) {
        Magic.pokeByte(common + DEVICE_STATUS, (byte) (status() | bits));
    }

    long deviceFeatures() {
        Magic.pokeInt(common + DEVICE_FEATURE_SELECT, 0);
        long low = Magic.peekInt(common + DEVICE_FEATURE) & 0xFFFF_FFFFL;
        Magic.pokeInt(common + DEVICE_FEATURE_SELECT, 1);
        return low | (long) Magic.peekInt(common + DEVICE_FEATURE) << 32;
    }

    /** Offers {@code features} and reports whether the device accepted them. */
    boolean negotiate(long features) {
        Magic.pokeInt(common + DRIVER_FEATURE_SELECT, 0);
        Magic.pokeInt(common + DRIVER_FEATURE, (int) features);
        Magic.pokeInt(common + DRIVER_FEATURE_SELECT, 1);
        Magic.pokeInt(common + DRIVER_FEATURE, (int) (features >>> 32));
        addStatus(FEATURES_OK);
        return (status() & FEATURES_OK) != 0;
    }

    /** Sets up queue {@code index} with at most {@link Virtqueue#SIZE} entries and enables it. */
    Virtqueue queue(int index) {
        Magic.pokeShort(common + QUEUE_SELECT, (short) index);
        int max = Magic.peekShort(common + QUEUE_SIZE) & 0xFFFF;
        if (max == 0) {
            throw new IllegalStateException("virtio queue " + index + " doesn't exist");
        }
        int size = Math.min(max, Virtqueue.SIZE);
        Magic.pokeShort(common + QUEUE_SIZE, (short) size);
        long doorbell = notify + (long) (Magic.peekShort(common + QUEUE_NOTIFY_OFF) & 0xFFFF) * notifyMultiplier;
        Virtqueue queue = new Virtqueue(index, size, doorbell);
        Magic.pokeLong(common + QUEUE_DESC, queue.descriptors());
        Magic.pokeLong(common + QUEUE_DRIVER, queue.available());
        Magic.pokeLong(common + QUEUE_DEVICE, queue.used());
        Magic.pokeShort(common + QUEUE_ENABLE, (short) 1);
        return queue;
    }
}
