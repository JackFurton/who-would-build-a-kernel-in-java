package duke.compiler.asm;

/**
 * A memory operand: {@code [base + index*scale + disp]}, {@code [rip + symbol + disp]} when base is
 * null, or {@code gs:[disp]} when {@code gs} is set.
 */
public record Mem(Reg base, Reg index, int scale, int disp, String symbol, boolean gs) {

    public Mem(Reg base, Reg index, int scale, int disp, String symbol) {
        this(base, index, scale, disp, symbol, false);
    }

    public Mem {
        if (base == null && symbol == null && !gs) {
            throw new IllegalArgumentException("memory operand needs a base register or a symbol");
        }
        if (index == Reg.RSP) {
            throw new IllegalArgumentException("rsp cannot be an index register");
        }
        if (scale != 1 && scale != 2 && scale != 4 && scale != 8) {
            throw new IllegalArgumentException("bad scale " + scale);
        }
    }

    public static Mem at(Reg base) {
        return new Mem(base, null, 1, 0, null);
    }

    public static Mem at(Reg base, int disp) {
        return new Mem(base, null, 1, disp, null);
    }

    public static Mem at(Reg base, Reg index, int scale, int disp) {
        return new Mem(base, index, scale, disp, null);
    }

    public static Mem rip(String symbol) {
        return new Mem(null, null, 1, 0, symbol);
    }

    public static Mem rip(String symbol, int disp) {
        return new Mem(null, null, 1, disp, symbol);
    }

    /** An absolute offset into the GS segment: the per-CPU block. */
    public static Mem gs(int disp) {
        return new Mem(null, null, 1, disp, null, true);
    }

    boolean isRipRelative() {
        return base == null && !gs;
    }
}
