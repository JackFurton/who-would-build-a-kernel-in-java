package duke.compiler.asm;

public enum Reg {
    RAX, RCX, RDX, RBX, RSP, RBP, RSI, RDI,
    R8, R9, R10, R11, R12, R13, R14, R15;

    int code() {
        return ordinal();
    }

    int low3() {
        return ordinal() & 7;
    }

    boolean extended() {
        return ordinal() >= 8;
    }
}
