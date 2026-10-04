import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Renders metrics.csv (from tools/Metrics.java) as small-multiple line charts, one SVG per color
 * scheme: {@code java tools/ProgressChart.java metrics.csv out-dir}. Each panel has its own
 * single y-axis; colors and chrome come from the dataviz reference palette, validated for these
 * three slots in both modes. Every line ends in a direct label because the light aqua slot sits
 * under 3:1 against the surface.
 */
public class ProgressChart {

    record Theme(String name, String surface, String primary, String secondary, String muted, String grid,
            String axis, String[] series) {}

    static final Theme LIGHT = new Theme("light", "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7",
            new String[] {"#2a78d6", "#eb6834", "#1baf7a"});
    static final Theme DARK = new Theme("dark", "#1a1a19", "#ffffff", "#c3c2b7", "#898781", "#2c2c2a", "#383835",
            new String[] {"#3987e5", "#d95926", "#199e70"});

    record Row(OffsetDateTime date, String commit, double[] values) {}

    record Series(String name, ToDoubleFunction<Row> value) {}

    record Panel(String title, String unit, List<Series> series) {}

    static final int WIDTH = 760;
    static final int PANEL_HEIGHT = 190;
    static final int GAP = 12;
    static final int LEFT = 52;
    static final int RIGHT = 150;
    static final int TOP = 54;
    static final int BOTTOM = 30;

    public static void main(String[] args) throws Exception {
        List<Row> rows = read(Path.of(args[0]));
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        List<Panel> panels = List.of(
                new Panel("Tests in the suites", "", List.of(
                        new Series("Conformance", r -> r.values()[0]),
                        new Series("Unit", r -> r.values()[2]),
                        new Series("Panic", r -> r.values()[1]))),
                new Panel("Boot kernel code size (.text)", " KB", List.of(
                        new Series(".text", r -> r.values()[3] / 1024.0))),
                new Panel("Lines of Java", "", List.of(
                        new Series("Compiler", r -> r.values()[6]),
                        new Series("Kernel", r -> r.values()[5]))));
        for (Theme theme : List.of(LIGHT, DARK)) {
            Files.writeString(out.resolve("progress-" + theme.name() + ".svg"), render(rows, panels, theme));
        }
    }

    static List<Row> read(Path csv) throws Exception {
        List<Row> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            double[] values = new double[f.length - 2];
            for (int i = 2; i < f.length; i++) {
                values[i - 2] = Double.parseDouble(f[i]);
            }
            rows.add(new Row(OffsetDateTime.parse(f[0]), f[1], values));
        }
        rows.sort(Comparator.comparing(Row::date));
        return rows;
    }

    static String render(List<Row> rows, List<Panel> panels, Theme t) {
        int height = panels.size() * PANEL_HEIGHT + (panels.size() - 1) * GAP;
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" role=\"img\" "
                        + "font-family=\"system-ui, -apple-system, 'Segoe UI', sans-serif\">\n",
                WIDTH, height, WIDTH, height));
        Row last = rows.getLast();
        svg.append("<title>Duke progress</title>\n<desc>").append(describe(panels, last)).append("</desc>\n");
        for (int p = 0; p < panels.size(); p++) {
            panel(svg, rows, panels.get(p), t, p * (PANEL_HEIGHT + GAP));
        }
        return svg.append("</svg>\n").toString();
    }

    static String describe(List<Panel> panels, Row last) {
        StringBuilder d = new StringBuilder("Latest (" + last.commit() + "): ");
        for (Panel panel : panels) {
            for (Series s : panel.series()) {
                d.append(s.name()).append(' ').append(format(s.value().applyAsDouble(last), panel.unit())).append("; ");
            }
        }
        return d.toString();
    }

    static void panel(StringBuilder svg, List<Row> rows, Panel panel, Theme t, int y0) {
        svg.append(String.format(Locale.ROOT,
                "<rect x=\"0\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"8\" fill=\"%s\"/>\n", y0, WIDTH, PANEL_HEIGHT, t.surface()));
        svg.append(text(16, y0 + 24, panel.title(), t.primary(), 14, "600", "start"));

        // Legend only when there's more than one series; the title names a single one.
        if (panel.series().size() > 1) {
            int x = 16;
            for (int i = 0; i < panel.series().size(); i++) {
                svg.append(String.format(Locale.ROOT,
                        "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" rx=\"2\" fill=\"%s\"/>\n", x, y0 + 34, t.series()[i]));
                String name = panel.series().get(i).name();
                svg.append(text(x + 15, y0 + 43, name, t.secondary(), 12, "400", "start"));
                x += 15 + name.length() * 7 + 18;
            }
        }

        double plotLeft = LEFT;
        double plotRight = WIDTH - RIGHT;
        double plotTop = y0 + TOP;
        double plotBottom = y0 + PANEL_HEIGHT - BOTTOM;

        double max = 0;
        for (Series s : panel.series()) {
            for (Row r : rows) {
                max = Math.max(max, s.value().applyAsDouble(r));
            }
        }
        double step = niceStep(max / 3);
        double top = Math.max(step, Math.ceil(max / step) * step);
        long t0 = rows.getFirst().date().toEpochSecond();
        long t1 = rows.getLast().date().toEpochSecond();
        double span = Math.max(1, t1 - t0);

        for (double v = 0; v <= top + 1e-9; v += step) {
            double y = plotBottom - v / top * (plotBottom - plotTop);
            String color = v == 0 ? t.axis() : t.grid();
            svg.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1\"/>\n",
                    plotLeft, plotRight, y, y, color));
            svg.append(text(plotLeft - 8, y + 4, format(v, ""), t.muted(), 11, "400", "end"));
        }

        for (OffsetDateTime tick : ticks(rows.getFirst().date(), rows.getLast().date())) {
            double x = plotLeft + (tick.toEpochSecond() - t0) / span * (plotRight - plotLeft);
            svg.append(text(x, plotBottom + 18, tickLabel(tick, t1 - t0), t.muted(), 11, "400", "middle"));
        }

        List<double[]> ends = new ArrayList<>();
        for (int i = 0; i < panel.series().size(); i++) {
            Series s = panel.series().get(i);
            StringBuilder points = new StringBuilder();
            double x = 0;
            double y = 0;
            for (Row r : rows) {
                x = plotLeft + (r.date().toEpochSecond() - t0) / span * (plotRight - plotLeft);
                y = plotBottom - s.value().applyAsDouble(r) / top * (plotBottom - plotTop);
                points.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
            }
            svg.append(String.format(Locale.ROOT,
                    "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>\n",
                    points.toString().strip(), t.series()[i]));
            svg.append(String.format(Locale.ROOT,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"2\"/>\n", x, y, t.series()[i], t.surface()));
            ends.add(new double[] {y, i});
        }

        // Direct labels at the line ends, nudged apart so they never overlap.
        ends.sort(Comparator.comparingDouble(e -> e[0]));
        double previous = Double.NEGATIVE_INFINITY;
        for (double[] end : ends) {
            double y = Math.max(end[0], previous + 15);
            previous = y;
            Series s = panel.series().get((int) end[1]);
            String label = (panel.series().size() > 1 ? s.name() + " " : "")
                    + format(s.value().applyAsDouble(rows.getLast()), panel.unit());
            svg.append(text(plotRight + 12, y + 4, label, t.secondary(), 12, "400", "start"));
        }
    }

    static List<OffsetDateTime> ticks(OffsetDateTime from, OffsetDateTime to) {
        return List.of(from, from.plus(Duration.between(from, to).dividedBy(2)), to);
    }

    static String tickLabel(OffsetDateTime t, long spanSeconds) {
        String pattern = spanSeconds < 36 * 3600 ? "MMM d HH:mm" : "MMM d";
        return t.format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
    }

    static double niceStep(double raw) {
        if (raw <= 0) {
            return 1;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : new double[] {1, 2, 2.5, 5, 10}) {
            if (m * magnitude >= raw) {
                return m * magnitude;
            }
        }
        return 10 * magnitude;
    }

    static String format(double v, String unit) {
        String number = v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
        return number + unit;
    }

    static String text(double x, double y, String s, String fill, int size, String weight, String anchor) {
        return String.format(Locale.ROOT,
                "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"%d\" font-weight=\"%s\" text-anchor=\"%s\" "
                        + "style=\"font-variant-numeric: tabular-nums\">%s</text>\n",
                x, y, fill, size, weight, anchor, s.replace("&", "&amp;").replace("<", "&lt;"));
    }
}
