package duke.compiler.image;

/** A reference from {@code offset} in a section to a symbol, resolved as {@code S + addend [- P]}. */
public record Reloc(int offset, Kind kind, String symbol, long addend) {

    public enum Kind {
        /** 32-bit signed PC-relative: S + A - P. */
        PC32,
        /** 64-bit absolute: S + A. */
        ABS64
    }
}
