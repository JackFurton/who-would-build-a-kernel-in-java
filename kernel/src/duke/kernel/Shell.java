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
        while (true) {
            int c = Input.take();
            if (c == '\n') {
                Console.println("");
                return line.toString();
            }
            if (c == '\b' || c == 0x7F) {
                if (line.length() > 0) {
                    line.setLength(line.length() - 1);
                    Console.print("\b \b");
                }
            } else if (c >= ' ' && c < 0x7F) {
                line.append((char) c);
                Serial.write(c);
            }
        }
    }

    static void execute(String line) {
        List<String> words = split(line);
        if (words.isEmpty()) {
            return;
        }
        switch (words.get(0)) {
            case "help" -> Console.println("commands: help, uptime, mem, gc, cpus, echo <text>, panic");
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
