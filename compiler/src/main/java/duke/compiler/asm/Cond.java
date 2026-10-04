package duke.compiler.asm;

/** x86 condition codes, in encoding order. */
public enum Cond {
    O, NO, B, AE, E, NE, BE, A, S, NS, P, NP, L, GE, LE, G;

    int code() {
        return ordinal();
    }

    public Cond negate() {
        return values()[ordinal() ^ 1];
    }
}
