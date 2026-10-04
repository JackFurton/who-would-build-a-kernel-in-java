import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Drives the real kernel's shell: boots it with the serial port on pipes and the QEMU monitor on
 * a Unix socket, types commands over serial and as PS/2 keystrokes (monitor "sendkey"), and checks
 * the replies. Covers IOAPIC routing, both interrupt handlers, the input ring and the shell.
 *
 * <p>Run from the repository root with {@code make shell-test}.
 */
public class ShellTest {

    static final Path ESP = Harness.ROOT.resolve("build/esp");
    static final Path OUT = Harness.ROOT.resolve("build/shell-test");
    static final long TIMEOUT_MILLIS = 120_000;

    static final StringBuffer output = new StringBuffer();

    public static void main(String[] args) throws Exception {
        Harness.deleteRecursively(OUT);
        Files.createDirectories(OUT);
        // Unix socket paths max out at 108 bytes, which a CI checkout path alone can come close to.
        Path monitor = Files.createTempDirectory("duke").resolve("monitor.sock");
        Process qemu = new ProcessBuilder("tools/qemu.sh", ESP.toString(), "-serial", "stdio",
                "-monitor", "unix:" + monitor + ",server=on,wait=off")
                .directory(Harness.ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        Thread reader = new Thread(() -> pump(qemu.getInputStream()));
        reader.setDaemon(true);
        reader.start();
        OutputStream serial = qemu.getOutputStream();
        int failures = 0;
        try {
            if (!await("duke> ", 0)) {
                qemu.destroy();
                qemu.waitFor(10, TimeUnit.SECONDS);
                System.out.println("shell-test: the kernel never reached its prompt; QEMU said:\n" + clean(output.toString()).indent(4));
                System.exit(1);
            }
            failures += check("serial", () -> type(serial, "echo hello over serial\n"), "hello over serial");
            failures += check("serial editing", () -> type(serial, "ecxx\u007f\u007fho fixed\n"), "fixed");
            failures += check("unknown command", () -> type(serial, "frobnicate\n"), "unknown command: frobnicate (try help)");
            failures += check("ps/2 keyboard", () -> keys(monitor, "u p t i m e ret"), "up ");
            failures += check("ps/2 shift", () -> keys(monitor, "e c h o spc shift-d u k e shift-1 ret"), "Duke!");
            failures += check("mem", () -> type(serial, "mem\n"), "frames: ");
            failures += check("panic", () -> type(serial, "panic\n"), "PANIC: requested from the shell");
        } finally {
            qemu.destroy();
            qemu.waitFor(10, TimeUnit.SECONDS);
            Files.writeString(OUT.resolve("serial.log"), output.toString());
        }
        System.out.println("shell-test: " + (7 - failures) + "/7 passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Action {
        void run() throws Exception;
    }

    /** Runs {@code action}, then waits for {@code expected} to appear after where output stood before. */
    static int check(String name, Action action, String expected) throws Exception {
        int from = output.length();
        action.run();
        if (await(expected, from)) {
            System.out.println("ok   " + name);
            return 0;
        }
        System.out.println("FAIL " + name + ": no \"" + expected + "\" in:\n" + clean(output.substring(from)).indent(4));
        return 1;
    }

    static boolean await(String text, int from) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (clean(output.substring(Math.min(from, output.length()))).contains(text)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    static void type(OutputStream serial, String text) throws Exception {
        serial.write(text.getBytes(StandardCharsets.ISO_8859_1));
        serial.flush();
    }

    /** Sends each space-separated QEMU key name through the human monitor's sendkey. */
    static void keys(Path monitor, String names) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(monitor));
            for (String key : names.split(" ")) {
                channel.write(ByteBuffer.wrap(("sendkey " + key + "\n").getBytes(StandardCharsets.US_ASCII)));
                // Give each key's press and release time to reach the guest before the next.
                Thread.sleep(80);
            }
        }
    }

    static void pump(InputStream in) {
        byte[] buffer = new byte[4096];
        try {
            for (int n; (n = in.read(buffer)) > 0; ) {
                output.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            }
        } catch (java.io.IOException e) {
            // QEMU exited.
        }
    }

    static String clean(String s) {
        return s.replaceAll("\\x1b\\[[0-9;=?]*[A-Za-z]", "").replace("\r", "");
    }
}
