package duke.kernel.virtio;

import duke.kernel.mm.PhysicalMemory;
import duke.kernel.pci.Pci;
import duke.rt.Magic;
import duke.rt.Tib;
import java.util.ArrayList;
import java.util.List;

/**
 * A virtio block device: reads and writes 512-byte sectors through one queue. Heap arrays aren't
 * physically contiguous, so data goes through a frame of its own, 8 sectors at a time.
 */
public final class VirtioBlock {

    public static final int SECTOR_SIZE = 512;

    private static final int TRANSITIONAL_ID = 0x1001;
    private static final int MODERN_ID = 0x1042;
    private static final long FEATURE_READ_ONLY = 1L << 5;
    private static final long FEATURE_VERSION_1 = 1L << 32;
    private static final int TYPE_IN = 0;
    private static final int TYPE_OUT = 1;
    private static final int STATUS_OK = 0;
    private static final int SECTORS_PER_REQUEST = (int) (PhysicalMemory.PAGE_SIZE / SECTOR_SIZE);

    private static final List<VirtioBlock> devices = new ArrayList<>();

    private final VirtioPci transport;
    private final Virtqueue queue;
    private final long capacity;
    private final boolean readOnly;
    private final long request;
    private final long data;

    private VirtioBlock(VirtioPci transport) {
        this.transport = transport;
        transport.reset();
        transport.addStatus(VirtioPci.ACKNOWLEDGE | VirtioPci.DRIVER);
        long offered = transport.deviceFeatures();
        if ((offered & FEATURE_VERSION_1) == 0 || !transport.negotiate(FEATURE_VERSION_1)) {
            transport.addStatus(VirtioPci.FAILED);
            throw new IllegalStateException("virtio-blk at " + transport.function.address() + " refused virtio 1.0");
        }
        readOnly = (offered & FEATURE_READ_ONLY) != 0;
        queue = transport.queue(0);
        transport.addStatus(VirtioPci.DRIVER_OK);
        capacity = Magic.peekLong(transport.deviceConfig());
        request = PhysicalMemory.allocateZeroed();
        data = PhysicalMemory.allocateZeroed();
    }

    public static void init() {
        for (Pci.Function f : Pci.functions()) {
            if (f.vendorId == VirtioPci.VENDOR && (f.deviceId == TRANSITIONAL_ID || f.deviceId == MODERN_ID)) {
                devices.add(new VirtioBlock(new VirtioPci(f)));
            }
        }
    }

    public static List<VirtioBlock> devices() {
        return devices;
    }

    public Pci.Function function() {
        return transport.function;
    }

    /** Size in sectors. */
    public long capacity() {
        return capacity;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /** Fills {@code buffer}, a whole number of sectors long, from the disk starting at {@code sector}. */
    public void read(long sector, byte[] buffer) {
        transfer(TYPE_IN, sector, buffer);
    }

    public void write(long sector, byte[] buffer) {
        if (readOnly) {
            throw new IllegalStateException("virtio-blk at " + transport.function.address() + " is read-only");
        }
        transfer(TYPE_OUT, sector, buffer);
    }

    private synchronized void transfer(int type, long sector, byte[] buffer) {
        int sectors = buffer.length / SECTOR_SIZE;
        if (buffer.length % SECTOR_SIZE != 0 || sector < 0 || sector + sectors > capacity) {
            throw new IllegalArgumentException(sectors + " sectors at " + sector + " on a disk of " + capacity);
        }
        long array = Magic.addressOf(buffer) + Tib.ARRAY_DATA;
        long header = PhysicalMemory.toVirtual(request);
        long staging = PhysicalMemory.toVirtual(data);
        for (int done = 0; done < sectors; done += SECTORS_PER_REQUEST) {
            int count = Math.min(SECTORS_PER_REQUEST, sectors - done);
            int bytes = count * SECTOR_SIZE;
            long offset = (long) done * SECTOR_SIZE;
            if (type == TYPE_OUT) {
                Magic.copyMemory(staging, array + offset, bytes);
            }
            Magic.pokeInt(header, type);
            Magic.pokeInt(header + 4, 0);
            Magic.pokeLong(header + 8, sector + done);
            Magic.pokeByte(header + 16, (byte) 0xFF);
            queue.descriptor(0, request, 16, Virtqueue.NEXT, 1);
            queue.descriptor(1, data, bytes, Virtqueue.NEXT | (type == TYPE_IN ? Virtqueue.WRITE : 0), 2);
            queue.descriptor(2, request + 16, 1, Virtqueue.WRITE, 0);
            queue.run(0);
            int status = Magic.peekByte(header + 16) & 0xFF;
            if (status != STATUS_OK) {
                throw new IllegalStateException("virtio-blk " + (type == TYPE_IN ? "read" : "write") + " of sector "
                        + (sector + done) + " failed with status " + status);
            }
            if (type == TYPE_IN) {
                Magic.copyMemory(array + offset, staging, bytes);
            }
        }
    }
}
