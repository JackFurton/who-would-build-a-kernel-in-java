package duke.kernel.x86;

import duke.kernel.Console;
import duke.kernel.Panic;
import duke.rt.Magic;

/**
 * Java side of interrupt handling. Handlers run on the interrupted stack with interrupts off and
 * must not allocate: the bump allocator isn't reentrant.
 */
public final class Interrupts {

    public interface Handler {
        void handle(long frame);
    }

    private static final Handler[] HANDLERS = new Handler[256];

    private static final String[] EXCEPTIONS = {
        "#DE divide error", "#DB debug", "NMI", "#BP breakpoint", "#OF overflow", "#BR bound range",
        "#UD invalid opcode", "#NM device not available", "#DF double fault", "coprocessor overrun",
        "#TS invalid TSS", "#NP segment not present", "#SS stack fault", "#GP general protection",
        "#PF page fault", "reserved", "#MF x87 error", "#AC alignment check", "#MC machine check",
        "#XM SIMD error", "#VE virtualization", "#CP control protection"};

    private Interrupts() {
    }

    public static void register(int vector, Handler handler) {
        HANDLERS[vector] = handler;
    }

    /** Called by the compiler's interrupt stubs (Compiler.emitInterruptStubs). */
    static void dispatch(long frame) {
        int vector = Frame.vector(frame);
        Handler handler = HANDLERS[vector];
        if (handler != null) {
            handler.handle(frame);
            return;
        }
        String name = vector < EXCEPTIONS.length ? EXCEPTIONS[vector] : vector < 32 ? "reserved exception" : "interrupt";
        String detail = vector == 14 ? " at address 0x" + Long.toHexString(Magic.readCr2()) : "";
        Panic.begin(name, " (vector ", Integer.toString(vector), ", error 0x", Long.toHexString(Frame.errorCode(frame)), ")", detail);
        dump(frame);
        Panic.haltForever();
    }

    private static void dump(long frame) {
        Console.println("  rip=0x" + Long.toHexString(Frame.rip(frame)) + " rsp=0x" + Long.toHexString(Frame.rsp(frame))
                + " rflags=0x" + Long.toHexString(Frame.rflags(frame)) + " cs=0x" + Long.toHexString(Frame.cs(frame)));
        StringBuilder line = new StringBuilder();
        for (int i = Frame.REGISTERS.length - 1; i >= 0; i--) {
            line.append("  ").append(Frame.REGISTERS[i]).append("=0x").append(Long.toHexString(Frame.register(frame, i)));
            if ((Frame.REGISTERS.length - i) % 4 == 0 || i == 0) {
                Console.println(line.toString());
                line.setLength(0);
            }
        }
    }
}
