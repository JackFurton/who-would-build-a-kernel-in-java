package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.fs.Fat;
import duke.kernel.virtio.VirtioBlock;
import java.util.List;

// tools/KernelTests.java attaches FatImage.sample() twice: as a FAT32 image from tools/FatImage.java,
// and as a directory QEMU serves as FAT16 (behind an MBR).
final class FatTest {

    private static Fat volume(boolean fat32) {
        for (VirtioBlock disk : VirtioBlock.devices()) {
            Fat fat = Fat.mount(disk);
            if (fat != null && fat.fat32() == fat32) {
                return fat;
            }
        }
        throw new AssertionError("no " + (fat32 ? "FAT32" : "FAT16") + " disk");
    }

    private static String text(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    private static Fat.Entry find(List<Fat.Entry> entries, String name) {
        for (Fat.Entry e : entries) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        throw new AssertionError(name + " missing");
    }

    private static void checkSample(Fat fat) {
        List<Fat.Entry> root = fat.list("/");
        assertEquals(17, find(root, "hello.txt").size, "hello.txt size");
        assertEquals(5000, find(root, "big.bin").size, "big.bin size");
        assertTrue(find(root, "docs").directory, "docs is a directory");
        find(root, "README.TXT");
        find(root, "exactly-twenty-six-chars.x");

        assertEquals("hello from FAT32\n", text(fat.read("hello.txt")), "hello.txt");
        assertEquals("short names only\n", text(fat.read("README.TXT")), "short name");
        assertEquals("long names work\n", text(fat.read("A Long File Name.md")), "long name");
        assertEquals("no terminator\n", text(fat.read("exactly-twenty-six-chars.x")), "name of exactly two parts");
        assertEquals(0, fat.read("empty.txt").length, "empty file");
        assertEquals("nested\n", text(fat.read("/Docs/DEEPER/notes.txt")), "nested, any case");
        assertEquals(17, fat.lookup("docs/deeper/../../hello.txt").size, "dot-dot back to the root");

        byte[] big = fat.read("big.bin");
        for (int i = 0; i < big.length; i++) {
            if (big[i] != (byte) (i * 7 + 3)) {
                assertEquals((byte) (i * 7 + 3), big[i], "big.bin byte " + i);
            }
        }
    }

    private static void checkErrors(Fat fat) {
        boolean threw = false;
        try {
            fat.read("nope.txt");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "missing file");
        threw = false;
        try {
            fat.read("docs");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "reading a directory");
        threw = false;
        try {
            fat.list("hello.txt/x");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "a file in the middle of a path");
    }

    // big.bin's clusters alternate with free ones in the FAT32 image, so its chain isn't contiguous.
    static void testReadsFat32() {
        Fat fat = volume(true);
        checkSample(fat);
        checkErrors(fat);
    }

    static void testReadsFat16BehindAnMbr() {
        Fat fat = volume(false);
        checkSample(fat);
        checkErrors(fat);
    }

    static void testTheTestDiskIsNotFat() {
        for (VirtioBlock disk : VirtioBlock.devices()) {
            if (disk.capacity() == 64) {
                assertTrue(Fat.mount(disk) == null, "patterned disk");
            }
        }
        assertTrue(Fat.mounted() != null, "boot mounted one of the FAT disks");
    }
}
