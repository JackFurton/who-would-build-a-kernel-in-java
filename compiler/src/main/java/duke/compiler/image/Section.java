package duke.compiler.image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A growable chunk of the output image with relocations against named symbols. */
public final class Section {

    public enum Kind { TEXT, RODATA, DATA, BSS }

    private final String name;
    private final Kind kind;
    private byte[] bytes = new byte[256];
    private int size;
    private int alignment = 1;
    private final List<Reloc> relocs = new ArrayList<>();

    public Section(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public int size() {
        return size;
    }

    public int alignment() {
        return alignment;
    }

    public List<Reloc> relocs() {
        return Collections.unmodifiableList(relocs);
    }

    /** Pads to a multiple of {@code align}; text is padded with int3 so stray jumps trap. */
    public void align(int align) {
        if (Integer.bitCount(align) != 1) {
            throw new IllegalArgumentException("alignment must be a power of two: " + align);
        }
        alignment = Math.max(alignment, align);
        int padded = (size + align - 1) & -align;
        if (kind == Kind.BSS) {
            size = padded;
            return;
        }
        byte fill = kind == Kind.TEXT ? (byte) 0xCC : 0;
        while (size < padded) {
            emit8(fill);
        }
    }

    public void reserve(int count) {
        if (kind == Kind.BSS) {
            size += count;
        } else {
            for (int i = 0; i < count; i++) {
                emit8(0);
            }
        }
    }

    public void emit8(int value) {
        if (kind == Kind.BSS) {
            throw new IllegalStateException("cannot emit initialized bytes into " + name);
        }
        if (size == bytes.length) {
            bytes = Arrays.copyOf(bytes, bytes.length * 2);
        }
        bytes[size++] = (byte) value;
    }

    public void emit16(int value) {
        emit8(value);
        emit8(value >>> 8);
    }

    public void emit32(int value) {
        emit16(value);
        emit16(value >>> 16);
    }

    public void emit64(long value) {
        emit32((int) value);
        emit32((int) (value >>> 32));
    }

    public void emitBytes(byte[] data) {
        for (byte b : data) {
            emit8(b);
        }
    }

    public int get8(int offset) {
        return bytes[offset] & 0xFF;
    }

    public void put32(int offset, int value) {
        for (int i = 0; i < 4; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    public void put64(int offset, long value) {
        for (int i = 0; i < 8; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    /** Emits a placeholder for a reference to {@code symbol}, patched at link time. */
    public void emitReloc(Reloc.Kind relocKind, String symbol, long addend) {
        relocs.add(new Reloc(size, relocKind, symbol, addend));
        if (relocKind == Reloc.Kind.ABS64) {
            emit64(0);
        } else {
            emit32(0);
        }
    }

    public byte[] toByteArray() {
        if (kind == Kind.BSS) {
            return new byte[0];
        }
        return Arrays.copyOf(bytes, size);
    }
}
