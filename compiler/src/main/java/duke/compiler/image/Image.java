package duke.compiler.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The four output sections plus the symbol table; {@link #link} lays them out and applies relocations. */
public final class Image {

    public static final long PAGE = 4096;

    public enum SymbolType { FUNC, OBJECT }

    public record Symbol(String name, Section section, int offset, int size, SymbolType type) {}

    public record Placed(Section section, long address, long fileOffset, byte[] bytes) {}

    public record Linked(List<Placed> sections, Map<String, Long> addresses, List<Symbol> symbols) {

        public long address(String symbol) {
            Long addr = addresses.get(symbol);
            if (addr == null) {
                throw new IllegalArgumentException("undefined symbol: " + symbol);
            }
            return addr;
        }
    }

    public final Section text = new Section(".text", Section.Kind.TEXT);
    public final Section rodata = new Section(".rodata", Section.Kind.RODATA);
    public final Section data = new Section(".data", Section.Kind.DATA);
    public final Section bss = new Section(".bss", Section.Kind.BSS);

    private final Map<String, Symbol> symbols = new LinkedHashMap<>();

    public void define(String name, Section section, int offset, int size, SymbolType type) {
        Symbol previous = symbols.putIfAbsent(name, new Symbol(name, section, offset, size, type));
        if (previous != null) {
            throw new IllegalStateException("duplicate symbol: " + name);
        }
    }

    public boolean isDefined(String name) {
        return symbols.containsKey(name);
    }

    /**
     * Places text, rodata and data on separate pages so each gets its own permissions, with bss
     * directly after data. File offsets mirror virtual offsets from {@code base}, starting one page
     * in so the ELF headers sit in an unloaded first page.
     */
    public Linked link(long base) {
        List<Section> order = List.of(text, rodata, data, bss);
        Map<Section, Long> sectionAddr = new LinkedHashMap<>();
        long addr = base;
        for (Section s : order) {
            if (s == bss) {
                addr = alignUp(addr, Math.max(s.alignment(), 8));
            } else {
                addr = alignUp(addr, PAGE);
            }
            sectionAddr.put(s, addr);
            addr += s.size();
        }

        Map<String, Long> addresses = new LinkedHashMap<>();
        for (Symbol sym : symbols.values()) {
            addresses.put(sym.name(), sectionAddr.get(sym.section()) + sym.offset());
        }

        List<Placed> placed = new ArrayList<>();
        for (Section s : order) {
            byte[] bytes = s.toByteArray();
            long sAddr = sectionAddr.get(s);
            for (Reloc r : s.relocs()) {
                Long target = addresses.get(r.symbol());
                if (target == null) {
                    throw new IllegalStateException("undefined symbol " + r.symbol() + " referenced from " + s.name());
                }
                long value = target + r.addend();
                switch (r.kind()) {
                    case ABS64 -> put(bytes, r.offset(), value, 8);
                    case PC32 -> {
                        long rel = value - (sAddr + r.offset());
                        if (rel != (int) rel) {
                            throw new IllegalStateException("PC32 relocation out of range: " + r.symbol());
                        }
                        put(bytes, r.offset(), rel, 4);
                    }
                }
            }
            placed.add(new Placed(s, sAddr, PAGE + (sAddr - base), bytes));
        }
        return new Linked(placed, addresses, List.copyOf(symbols.values()));
    }

    private static void put(byte[] bytes, int offset, long value, int width) {
        for (int i = 0; i < width; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    static long alignUp(long value, long align) {
        return (value + align - 1) & -align;
    }
}
