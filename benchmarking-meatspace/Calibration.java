import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The calibration committee (#111): levels everyone from what they've merged to main, counts their
 * open PRs as in review, and projects 30 days out. {@code java benchmarking-meatspace/Calibration.java
 * [out-dir]} prints the table; with an out-dir it also writes calibration-{light,dark}.svg and
 * external-race-{light,dark}.svg. The metrics job runs it on every push to main. Open PRs come from
 * {@code gh} and are skipped when it isn't available. The rules are in the README next to this file;
 * change them in both places.
 */
public class Calibration {

    enum Area {
        RUNTIME(3.0, "Runtime and GC"),
        COMPILER(2.5, "Compilers"),
        KERNEL(2.0, "Device Drivers"),
        JAVASCRIPT(2.0, "JavaScript"),
        LIBRARY(1.5, "Standard Library"),
        TESTS(1.0, "Quality"),
        INFRA(0.7, "Developer Experience");

        final double weight;
        final String specialty;

        Area(double weight, String specialty) {
            this.weight = weight;
            this.specialty = specialty;
        }
    }

    /** A merged change that says it's about speed targets the paths everything runs through. */
    static final double PERF_MULTIPLIER = 3.0;
    static final double TEST_BONUS = 1.2;
    static final int PROJECTION_DAYS = 30;
    /** One good day isn't a trend: pace is measured over at least this long. */
    static final int MIN_PACE_DAYS = 7;

    record Level(String name, String title, double from) {}

    static final List<Level> LEVELS = List.of(
            new Level("L8", "Principal Engineer", 800),
            new Level("L7", "Senior Staff Engineer", 350),
            new Level("L6", "Staff Engineer", 150),
            new Level("L5", "Senior Software Engineer", 60),
            new Level("L4", "Software Engineer II", 25),
            new Level("L3", "Software Engineer", 0));

    /** Sets the calibration rather than being calibrated. */
    static final String CEO = "JackFurton";
    /** The CEO's friends from before the repo existed: leveled, but not in the external race. */
    static final Set<String> FOUNDING_TEAM = Set.of("dkempner", "Firebathero");

    /** Git author names that aren't GitHub handles. Noreply addresses carry the handle themselves. */
    static final Map<String, String> HANDLES = Map.of(
            "Krog", "JackFurton",
            "Jack Furton", "JackFurton",
            "superorganism labs", "Firebathero",
            "Senthil Kumar Rajendran", "senthilkumar-r");

    static final Pattern NOREPLY = Pattern.compile("^(?:\\d+\\+)?([A-Za-z0-9-]+)@users\\.noreply\\.github\\.com$");
    static final Pattern PERF = Pattern.compile("(?i)^perf\\b|\\bfaster\\b|\\bspeed");
    /** A written performance review in the README starts with the handle in bold. */
    static final Pattern REVIEW = Pattern.compile("(?m)^\\*\\*([A-Za-z0-9-]+)\\*\\*,");
    static final Path README = Path.of("benchmarking-meatspace/README.md");

    record Event(OffsetDateTime when, double impact) {}

    static final class Person {
        final String handle;
        final Map<Area, Double> byArea = new EnumMap<>(Area.class);
        final List<Event> merged = new ArrayList<>();
        double impact;
        double perfImpact;
        double inReview;
        int openPrs;
        int lines;

        Person(String handle) {
            this.handle = handle;
        }

        OffsetDateTime first() {
            return merged.isEmpty() ? null : merged.get(0).when();
        }

        Level level() {
            return levelFor(impact);
        }

        String title() {
            String specialty = perfImpact >= impact / 2 && impact > 0 ? "Performance"
                    : byArea.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey().specialty).orElse("Generalist");
            return level().title() + ", " + specialty;
        }

        double pace(OffsetDateTime now) {
            if (merged.isEmpty()) {
                return 0;
            }
            double days = Math.max(MIN_PACE_DAYS, Duration.between(first(), now).toHours() / 24.0);
            return impact / days;
        }

        double projected(OffsetDateTime now) {
            return impact + inReview + pace(now) * PROJECTION_DAYS;
        }

        boolean external() {
            return !handle.equals(CEO) && !FOUNDING_TEAM.contains(handle);
        }
    }

    static Level levelFor(double impact) {
        for (Level level : LEVELS) {
            if (impact >= level.from()) {
                return level;
            }
        }
        return LEVELS.get(LEVELS.size() - 1);
    }

    /** What the promo packet says: when the next level arrives, if it does. */
    static String promo(Person p, OffsetDateTime now) {
        int index = LEVELS.indexOf(p.level());
        if (index == 0) {
            return "top of the ladder";
        }
        Level next = LEVELS.get(index - 1);
        if (p.impact + p.inReview >= next.from()) {
            return next.name() + " promo when their open PRs merge";
        }
        if (p.pace(now) == 0) {
            return next.name() + " promo: ship something first";
        }
        long days = (long) Math.ceil((next.from() - p.impact - p.inReview) / p.pace(now));
        return String.format(Locale.ROOT, "%s promo in ~%d days", next.name(), days);
    }

    public static void main(String[] args) throws Exception {
        Map<String, Person> people = new LinkedHashMap<>();
        readHistory(people);
        boolean pipeline = readOpenPullRequests(people);
        OffsetDateTime now = OffsetDateTime.now();
        List<Person> ranked = new ArrayList<>(people.values());
        ranked.removeIf(p -> p.handle.equals(CEO));
        ranked.sort(Comparator.comparingDouble((Person p) -> p.impact + p.inReview).reversed());

        System.out.println(CEO + ": Founder & CEO, sets the calibration");
        for (Person p : ranked) {
            System.out.printf(Locale.ROOT, "%-3s %-16s %6.1f merged %6.1f in review  30d %6.1f  %-45s %s%n",
                    p.level().name(), p.handle, p.impact, p.inReview, p.projected(now), p.title(), promo(p, now));
        }
        if (!pipeline) {
            System.out.println("(open PRs skipped: gh unavailable)");
        }
        if (args.length > 0) {
            Path out = Path.of(args[0]);
            Files.createDirectories(out);
            List<Person> external = ranked.stream().filter(Person::external).toList();
            OffsetDateTime start = earliest(people.values());
            List<String> pending = pendingReviews(ranked);
            for (Theme theme : List.of(LIGHT, DARK)) {
                Files.writeString(out.resolve("calibration-" + theme.name() + ".svg"), calibration(ranked, pending, theme, now));
                Files.writeString(out.resolve("external-race-" + theme.name() + ".svg"), race(external, theme, start, now));
            }
        }
    }

    /** Calibrated people the README has no written review for yet. */
    static List<String> pendingReviews(List<Person> people) throws Exception {
        Set<String> reviewed = new java.util.HashSet<>();
        if (Files.exists(README)) {
            Matcher m = REVIEW.matcher(Files.readString(README));
            while (m.find()) {
                reviewed.add(m.group(1));
            }
        }
        return people.stream().map(p -> p.handle).filter(h -> !reviewed.contains(h)).toList();
    }

    // ---- scoring

    /** Every first-parent commit on HEAD is a merged PR, squashed. Oldest first. */
    static void readHistory(Map<String, Person> people) throws Exception {
        Process git = new ProcessBuilder("git", "log", "--first-parent", "--no-merges", "--reverse", "--numstat",
                "--format=@@%an%x1f%ae%x1f%aI%x1f%s", "HEAD").start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(git.getInputStream(), StandardCharsets.UTF_8))) {
            String header = null;
            List<String[]> files = new ArrayList<>();
            for (String line = in.readLine(); ; line = in.readLine()) {
                if (line == null || line.startsWith("@@")) {
                    if (header != null) {
                        String[] f = header.split("\u001f", 4);
                        Map<String, int[]> changes = new LinkedHashMap<>();
                        for (String[] file : files) {
                            if (!file[0].equals("-")) {
                                changes.put(file[2], new int[] {Integer.parseInt(file[0]), Integer.parseInt(file[1])});
                            }
                        }
                        Person p = people.computeIfAbsent(handle(f[0], f[1]), Person::new);
                        double impact = score(p, f[3], changes, true);
                        p.merged.add(new Event(OffsetDateTime.parse(f[2]), impact));
                    }
                    if (line == null) {
                        break;
                    }
                    header = line.substring(2);
                    files = new ArrayList<>();
                } else if (!line.isBlank()) {
                    files.add(line.split("\t", 3));
                }
            }
        }
        if (git.waitFor() != 0) {
            throw new IllegalStateException("git log failed");
        }
    }

    /** Open, non-draft PRs count as in review. False if gh isn't there or can't reach GitHub. */
    static boolean readOpenPullRequests(Map<String, Person> people) {
        try {
            Process gh = new ProcessBuilder("gh", "pr", "list", "--state", "open", "--limit", "100", "--json",
                    "author,title,isDraft,files", "--template",
                    "{{range .}}{{if not .isDraft}}@@{{.author.login}}\u001f{{.title}}\n"
                            + "{{range .files}}{{.additions}}\t{{.deletions}}\t{{.path}}\n{{end}}{{end}}{{end}}")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(gh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (gh.waitFor() != 0) {
                return false;
            }
            for (String pr : output.split("@@")) {
                if (pr.isBlank()) {
                    continue;
                }
                String[] lines = pr.split("\n");
                String[] head = lines[0].split("\u001f", 2);
                Map<String, int[]> changes = new LinkedHashMap<>();
                for (int i = 1; i < lines.length; i++) {
                    String[] file = lines[i].split("\t", 3);
                    if (file.length == 3) {
                        changes.put(file[2], new int[] {Integer.parseInt(file[0]), Integer.parseInt(file[1])});
                    }
                }
                Person p = people.computeIfAbsent(head[0], Person::new);
                p.inReview += score(p, head[1], changes, false);
                p.openPrs++;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Per area touched: weight × log2(1 + lines). Merged work also feeds the specialty. */
    static double score(Person p, String title, Map<String, int[]> changes, boolean merged) {
        Map<Area, Integer> lines = new EnumMap<>(Area.class);
        boolean code = false;
        boolean tests = false;
        int total = 0;
        for (Map.Entry<String, int[]> change : changes.entrySet()) {
            int changed = change.getValue()[0] + change.getValue()[1];
            Area area = area(change.getKey());
            lines.merge(area, changed, Integer::sum);
            total += changed;
            tests |= area == Area.TESTS || change.getKey().contains("/test/");
            code |= area != Area.TESTS && area != Area.INFRA;
        }
        boolean perf = PERF.matcher(title).find();
        double multiplier = (perf ? PERF_MULTIPLIER : 1) * (code && tests ? TEST_BONUS : 1);
        double impact = 0;
        for (Map.Entry<Area, Integer> e : lines.entrySet()) {
            double part = e.getKey().weight * Math.log1p(e.getValue()) / Math.log(2) * multiplier;
            impact += part;
            if (merged) {
                p.byArea.merge(e.getKey(), part, Double::sum);
            }
        }
        if (merged) {
            p.impact += impact;
            p.perfImpact += perf ? impact : 0;
            p.lines += total;
        }
        return impact;
    }

    static String handle(String name, String email) {
        if (HANDLES.containsKey(name)) {
            return HANDLES.get(name);
        }
        Matcher m = NOREPLY.matcher(email);
        return m.matches() ? m.group(1) : name;
    }

    static Area area(String path) {
        if (path.contains("/duke/js/") || path.endsWith(".js") || path.startsWith("tests/js/") || path.contains("/Js")) {
            return Area.JAVASCRIPT;
        }
        if (path.startsWith("tests/") || path.contains("/src/test/")) {
            return Area.TESTS;
        }
        if (path.startsWith("kernel/src/duke/rt/")) {
            return Area.RUNTIME;
        }
        if (path.startsWith("compiler/src/main/")) {
            return Area.COMPILER;
        }
        if (path.startsWith("kernel/src/java/")) {
            return Area.LIBRARY;
        }
        if (path.startsWith("kernel/src/")) {
            return Area.KERNEL;
        }
        return Area.INFRA;
    }

    static OffsetDateTime earliest(Iterable<Person> people) {
        OffsetDateTime first = null;
        for (Person p : people) {
            if (p.first() != null && (first == null || p.first().isBefore(first))) {
                first = p.first();
            }
        }
        return first == null ? OffsetDateTime.now() : first;
    }

    // ---- rendering

    record Theme(String name, String surface, String primary, String secondary, String muted, String grid,
            String band, String[] levels, String[] series, String crown) {}

    /** Level colors, L8 down to L3; series colors for the race, in a fixed order. */
    static final Theme LIGHT = new Theme("light", "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#f3f2ee",
            new String[] {"#c2185b", "#eb6834", "#7c5cd6", "#2a78d6", "#1baf7a", "#898781"},
            new String[] {"#2a78d6", "#eb6834", "#1baf7a", "#7c5cd6", "#c2185b"}, "#d4a017");
    static final Theme DARK = new Theme("dark", "#1a1a19", "#ffffff", "#c3c2b7", "#898781", "#2c2c2a", "#232322",
            new String[] {"#e0457f", "#d95926", "#9a7ef0", "#3987e5", "#199e70", "#898781"},
            new String[] {"#3987e5", "#d95926", "#199e70", "#9a7ef0", "#e0457f"}, "#f0c040");

    static String levelColor(Theme t, Level level) {
        return t.levels()[LEVELS.indexOf(level)];
    }

    static final int WIDTH = 820;

    /** One row per person: level badge, title, and stacked bars for merged, in review and 30 days on. */
    static String calibration(List<Person> people, List<String> pending, Theme t, OffsetDateTime now) {
        int top = 92;
        int row = 64;
        int left = 300;
        int right = WIDTH - 36;
        int bottom = top + Math.max(1, people.size()) * row;
        int height = bottom + (pending.isEmpty() ? 64 : 84);
        double max = 60;
        for (Person p : people) {
            max = Math.max(max, p.projected(now));
        }
        double scaleTop = niceCeiling(max);
        StringBuilder svg = open(height, "Calibration", describe(people), t);
        svg.append(text(16, 28, "Calibration", t.primary(), 18, "700", "start"));
        svg.append(text(16, 48, "Leveled on merged code. Darker: in review. Faded: 30 days at today's pace.",
                t.secondary(), 12, "400", "start"));

        // Level bands across the plot, labeled at the top.
        for (int i = LEVELS.size() - 1; i >= 0; i--) {
            Level level = LEVELS.get(i);
            if (level.from() > scaleTop) {
                continue;
            }
            double x0 = scale(level.from(), scaleTop, left, right);
            double x1 = i == 0 || LEVELS.get(i - 1).from() > scaleTop ? right : scale(LEVELS.get(i - 1).from(), scaleTop, left, right);
            if ((LEVELS.size() - 1 - i) % 2 == 1) {
                svg.append(rect(x0, top - 18, x1 - x0, bottom - top + 18, 0, t.band()));
            }
            if (x1 - x0 >= 24) {
                svg.append(text((x0 + x1) / 2, top - 6, level.name(), levelColor(t, level), 11, "700", "middle"));
            }
        }
        for (int i = 0; i < people.size(); i++) {
            Person p = people.get(i);
            int y = top + i * row;
            Level level = p.level();
            svg.append(rect(16, y + 8, 34, 26, 5, levelColor(t, level)));
            svg.append(text(33, y + 26, level.name(), "#ffffff", 13, "700", "middle"));
            svg.append(text(60, y + 19, p.handle, t.primary(), 14, "700", "start"));
            svg.append(text(60, y + 35, p.title(), t.secondary(), 11, "400", "start"));
            String review = p.openPrs == 0 ? "" : " · " + p.openPrs + " in review";
            svg.append(text(60, y + 50, p.merged.size() + " merged" + review + " · " + promo(p, now), t.muted(), 10, "400", "start"));

            double xMerged = scale(p.impact, scaleTop, left, right);
            double xReview = scale(p.impact + p.inReview, scaleTop, left, right);
            double xLater = scale(p.projected(now), scaleTop, left, right);
            String color = levelColor(t, level);
            svg.append(rect(left, y + 12, Math.max(2, xLater - left), 20, 5, color, 0.22));
            svg.append(rect(left, y + 12, Math.max(2, xReview - left), 20, 5, color, 0.55));
            svg.append(rect(left, y + 12, Math.max(2, xMerged - left), 20, 5, color));
            svg.append(text(Math.min(xLater + 6, right - 2), y + 27, String.format(Locale.ROOT, "%.0f", p.impact),
                    t.primary(), 11, "700", xLater + 40 > right ? "end" : "start"));
        }
        for (double tick = 0; tick <= scaleTop; tick += scaleTop / 4) {
            double x = scale(tick, scaleTop, left, right);
            svg.append(text(x, bottom + 16, String.format(Locale.ROOT, "%.0f", tick), t.muted(), 10, "400", "middle"));
        }
        svg.append(text(16, bottom + 44, CEO + ": Founder & CEO. Not calibrated; does the calibrating.",
                t.muted(), 11, "400", "start"));
        if (!pending.isEmpty()) {
            svg.append(text(16, bottom + 64, "Review pending: " + String.join(", ", pending)
                    + ". The committee has opened a file.", t.muted(), 11, "400", "start"));
        }
        return svg.append("</svg>\n").toString();
    }

    /** Impact over time for everyone outside the founding team, with dashed lines 30 days out. */
    static String race(List<Person> people, Theme t, OffsetDateTime start, OffsetDateTime now) {
        int height = 400;
        int left = 56;
        int right = WIDTH - 170;
        int top = 90;
        int bottom = height - 44;
        StringBuilder svg = open(height, "The external race", describe(people), t);
        svg.append(text(16, 28, "The external race", t.primary(), 18, "700", "start"));
        svg.append(text(16, 48, "Everyone outside the founding team. Dashed: 30 days at today's pace, open PRs merged.",
                t.secondary(), 12, "400", "start"));
        if (people.isEmpty()) {
            svg.append(text(WIDTH / 2.0, height / 2.0, "No external contributors yet. The door is open.", t.muted(), 14, "400", "middle"));
            return svg.append("</svg>\n").toString();
        }
        OffsetDateTime end = now.plusDays(PROJECTION_DAYS);
        double span = Duration.between(start, end).toMinutes();
        double max = 40;
        for (Person p : people) {
            max = Math.max(max, p.projected(now));
        }
        double scaleTop = niceCeiling(max);
        for (Level level : LEVELS) {
            if (level.from() > 0 && level.from() <= scaleTop) {
                double y = bottom - (bottom - top) * level.from() / scaleTop;
                svg.append(String.format(Locale.ROOT,
                        "<line x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-dasharray=\"2 4\"/>\n",
                        left, right, y, y, levelColor(t, level)));
                svg.append(text(left - 6, y + 4, level.name(), levelColor(t, level), 10, "700", "end"));
            }
        }
        double xNow = left + (right - left) * Duration.between(start, now).toMinutes() / span;
        svg.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\" stroke=\"%s\"/>\n",
                xNow, xNow, top - 8, bottom, t.grid()));
        svg.append(text(xNow, top - 12, "today", t.muted(), 10, "400", "middle"));
        svg.append(String.format(Locale.ROOT, "<line x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\" stroke=\"%s\"/>\n",
                left, right, bottom, bottom, t.grid()));
        DateTimeFormatter day = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
        svg.append(text(left, bottom + 18, start.format(day), t.muted(), 10, "400", "start"));
        svg.append(text(right, bottom + 18, end.format(day), t.muted(), 10, "400", "end"));

        Person leader = people.stream().max(Comparator.comparingDouble(p -> p.projected(now))).orElseThrow();
        List<double[]> labels = new ArrayList<>();
        for (int i = 0; i < people.size(); i++) {
            Person p = people.get(i);
            String color = t.series()[i % t.series().length];
            boolean lead = p == leader;
            StringBuilder path = new StringBuilder();
            double y = bottom;
            double cumulative = 0;
            if (p.first() != null) {
                double x = left + (right - left) * Duration.between(start, p.first()).toMinutes() / span;
                path.append(String.format(Locale.ROOT, "M%.1f,%.1f", x, y));
                for (Event e : p.merged) {
                    x = left + (right - left) * Duration.between(start, e.when()).toMinutes() / span;
                    path.append(String.format(Locale.ROOT, " H%.1f", x));
                    cumulative += e.impact();
                    y = bottom - (bottom - top) * cumulative / scaleTop;
                    path.append(String.format(Locale.ROOT, " V%.1f", y));
                }
                path.append(String.format(Locale.ROOT, " H%.1f", xNow));
            } else {
                path.append(String.format(Locale.ROOT, "M%.1f,%.1f", xNow, y));
            }
            svg.append(String.format(Locale.ROOT,
                    "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>\n",
                    path, color, lead ? "3.5" : "2"));
            double yEnd = bottom - (bottom - top) * p.projected(now) / scaleTop;
            svg.append(String.format(Locale.ROOT,
                    "<line x1=\"%.1f\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\" stroke-dasharray=\"6 5\"/>\n",
                    xNow, right, y, yEnd, color, lead ? "3" : "2"));
            svg.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"2\"/>\n",
                    xNow, y, lead ? "5" : "4", color, t.surface()));
            labels.add(new double[] {yEnd, i});
        }
        // End labels, nudged apart so they don't overlap.
        labels.sort(Comparator.comparingDouble(l -> l[0]));
        double last = -100;
        for (double[] label : labels) {
            double y = Math.max(label[0], last + 30);
            last = y;
            Person p = people.get((int) label[1]);
            String color = t.series()[(int) label[1] % t.series().length];
            boolean lead = p == leader;
            String name = (lead ? "♛ " : "") + p.handle;
            svg.append(text(right + 10, y + 1, name, lead ? t.crown() : color, 13, "700", "start"));
            svg.append(text(right + 10, y + 15, p.level().name() + " → " + levelFor(p.projected(now)).name()
                    + (lead ? " · alpha" : ""), t.secondary(), 10, "400", "start"));
        }
        return svg.append("</svg>\n").toString();
    }

    static String describe(List<Person> people) {
        StringBuilder sb = new StringBuilder();
        for (Person p : people) {
            sb.append(String.format(Locale.ROOT, "%s: %s, %s, %.0f merged, %.0f in review. ",
                    p.handle, p.level().name(), p.title(), p.impact, p.inReview));
        }
        return sb.toString();
    }

    static StringBuilder open(int height, String title, String description, Theme t) {
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" role=\"img\" "
                        + "font-family=\"system-ui, -apple-system, 'Segoe UI', sans-serif\">\n", WIDTH, height, WIDTH, height));
        svg.append("<title>").append(escape(title)).append("</title>\n<desc>").append(escape(description)).append("</desc>\n");
        svg.append(rect(0, 0, WIDTH, height, 0, t.surface()));
        return svg;
    }

    static double niceCeiling(double value) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        for (double step : new double[] {1, 2, 2.5, 5, 10}) {
            if (step * magnitude >= value) {
                return step * magnitude;
            }
        }
        return 10 * magnitude;
    }

    static double scale(double value, double top, int left, int right) {
        return left + (right - left) * Math.min(value, top) / top;
    }

    static String rect(double x, double y, double w, double h, int radius, String fill) {
        return rect(x, y, w, h, radius, fill, 1);
    }

    static String rect(double x, double y, double w, double h, int radius, String fill, double opacity) {
        return String.format(Locale.ROOT,
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%d\" fill=\"%s\"%s/>\n",
                x, y, w, h, radius, fill, opacity < 1 ? String.format(Locale.ROOT, " fill-opacity=\"%.2f\"", opacity) : "");
    }

    static String text(double x, double y, String s, String fill, int size, String weight, String anchor) {
        return String.format(Locale.ROOT,
                "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"%d\" font-weight=\"%s\" text-anchor=\"%s\">%s</text>\n",
                x, y, fill, size, weight, anchor, escape(s));
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
