package duke.kernel;

import duke.boot.Limine;
import duke.rt.Magic;

/**
 * Text on Limine's framebuffer: 8x16 Spleen glyphs, light gray on black, scrolling by moving the
 * pixels up a row. Assumes 32 bits per pixel, which is what UEFI GOP hands out in practice.
 */
public final class FramebufferConsole {

    public static final int FOREGROUND = 0x00C8C8C8;

    private static long base;
    private static long pitch;
    private static int columns;
    private static int rows;
    private static int column;
    private static int row;

    private FramebufferConsole() {
    }

    public static boolean init() {
        if (Limine.framebufferAddress() == 0 || Limine.framebufferBitsPerPixel() != 32) {
            return false;
        }
        pitch = Limine.framebufferPitch();
        columns = (int) (Limine.framebufferWidth() / Font.WIDTH);
        rows = (int) (Limine.framebufferHeight() / Font.HEIGHT);
        base = Limine.framebufferAddress();
        clear();
        return true;
    }

    public static int columns() {
        return columns;
    }

    public static int rows() {
        return rows;
    }

    public static void clear() {
        if (base == 0) {
            return;
        }
        Magic.fillMemory(base, 0, rows * Font.HEIGHT * pitch);
        column = 0;
        row = 0;
    }

    public static void write(int c) {
        if (base == 0) {
            return;
        }
        switch (c) {
            case '\n' -> newline();
            case '\r' -> column = 0;
            case '\b' -> {
                if (column > 0) {
                    column--;
                }
            }
            default -> {
                draw(c & 0xFF, column, row);
                if (++column == columns) {
                    newline();
                }
            }
        }
    }

    private static void newline() {
        column = 0;
        if (++row == rows) {
            long rowBytes = Font.HEIGHT * pitch;
            Magic.copyMemory(base, base + rowBytes, (rows - 1) * rowBytes);
            Magic.fillMemory(base + (rows - 1) * rowBytes, 0, rowBytes);
            row = rows - 1;
        }
    }

    private static void draw(int c, int x, int y) {
        long topLeft = base + (long) y * Font.HEIGHT * pitch + (long) x * Font.WIDTH * 4;
        for (int line = 0; line < Font.HEIGHT; line++) {
            int bits = Font.GLYPHS.charAt(c * Font.HEIGHT + line);
            long pixel = topLeft + line * pitch;
            for (int dx = 0; dx < Font.WIDTH; dx++) {
                Magic.pokeInt(pixel + 4L * dx, (bits & (0x80 >> dx)) != 0 ? FOREGROUND : 0);
            }
        }
    }
}
