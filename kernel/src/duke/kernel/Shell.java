package duke.kernel;

import duke.kernel.acpi.Madt;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.time.Timer;
import duke.rt.Heap;
import java.util.ArrayList;
import java.util.List;

/** A line-editing shell over every input device. */
public final class Shell {

    public static final String PROMPT = "duke> ";

    private Shell() {
    }

    public static void run() {
        while (true) {
            Console.print(PROMPT);
            String line = readLine();
            try {
                execute(line.strip());
            } catch (RuntimeException e) {
                Console.println("error: " + e);
            }
        }
    }

    private static String readLine() {
        StringBuilder line = new StringBuilder();
        int pos = 0;

        while (true) {
            int c = Input.take();
            if (c == '\n') {
                Console.println("");
                return line.toString();
            }

            switch (c) {
                case Input.KEY_LEFT -> {
                    if (pos > 0) {
                        pos--;
                        Console.print("\b");
                    }
                }
                case Input.KEY_RIGHT -> {
                    if (pos < line.length()) {
                        Console.write(line.charAt(pos));
                        pos++;
                    }
                }
                case Input.KEY_HOME -> {
                    while (pos > 0) {
                        pos--;
                        Console.print("\b");
                    }
                }
                case Input.KEY_END -> {
                    while (pos < line.length()) {
                        Console.write(line.charAt(pos));
                        pos++;
                    }
                }
                case Input.KEY_DELETE -> {
                    if (pos < line.length()) {
                        line.deleteCharAt(pos);
                        redrawFrom(line, pos);
                    }
                }
                case '\b', 0x7F -> {
                    if (pos > 0) {
                        line.deleteCharAt(pos - 1);
                        pos--;
                        Console.print("\b");
                        redrawFrom(line, pos);
                    }
                }
                default -> {
                    if (c >= ' ' && c < 0x7F) {
                        line.insert(pos, (char) c);
                        pos++;
                        Console.write(c);
                        redrawFrom(line, pos);
                    }
                }
            }
        }
    }

    private static void redrawFrom(StringBuilder line, int pos) {
        for (int i = pos; i < line.length(); i++) {
            Console.write(line.charAt(i));
        }
        Console.print(" ");
        int backSteps = line.length() - pos + 1;
        for (int i = 0; i < backSteps; i++) {
            Console.print("\b");
        }
    }

    static void execute(String line) {
        List<String> words = split(line);
        if (words.isEmpty()) {
            return;
        }
        switch (words.get(0)) {
            case "help" -> Console.println("commands: help, uptime, mem, gc, cpus, echo <text>, clear, panic");
            case "clear" -> FramebufferConsole.clear();
            case "uptime" -> Console.println("up " + Timer.uptimeMillis() + " ms (" + Timer.ticks() + " ticks)");
            case "mem" -> Console.println("frames: " + PhysicalMemory.freeFrames() + " free ("
                    + (PhysicalMemory.freeFrames() * PhysicalMemory.PAGE_SIZE >> 20) + " MiB); heap: "
                    + (Heap.used() >> 10) + " KiB used, " + (Heap.committed() >> 10) + " KiB committed");
            case "gc" -> {
                System.gc();
                Console.println("collections: " + Heap.collections() + ", live after last: " + (Heap.lastLive() >> 10) + " KiB");
            }
            case "cpus" -> {
                for (Madt.LocalApic cpu : Madt.cpus()) {
                    Console.println("cpu " + cpu.processorId + ": apic " + cpu.apicId + (cpu.enabled ? "" : " (disabled)"));
                }
            }
            case "echo" -> Console.println(line.substring(4).strip());
            case "panic" -> Panic.panic("requested from the shell");
            default -> Console.println("unknown command: " + words.get(0) + " (try help)");
        }
    }

    private static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= line.length(); i++) {
            boolean space = i == line.length() || line.charAt(i) == ' ';
            if (space && start >= 0) {
                words.add(line.substring(start, i));
                start = -1;
            } else if (!space && start < 0) {
                start = i;
            }
        }
        return words;
    }
}
