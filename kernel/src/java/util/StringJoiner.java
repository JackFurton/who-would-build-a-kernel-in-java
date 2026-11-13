package java.util;

/**
 * Joins strings with a delimiter, and an optional prefix and suffix. Duke has no CharSequence yet,
 * so this takes String where the JDK takes CharSequence; the calls read the same in source.
 */
public final class StringJoiner {

    private final String prefix;
    private final String delimiter;
    private final String suffix;

    // Everything added so far, prefix included, but never the suffix. toString appends the suffix
    // on demand and strips it again, so more elements can still follow afterwards. Null until the
    // first add, which is how toString tells an empty joiner from one holding an empty string.
    private StringBuilder value;

    // What toString returns while nothing has been added. The JDK defaults this to prefix + suffix
    // and lets setEmptyValue replace it.
    private String emptyValue;

    public StringJoiner(String delimiter) {
        this(delimiter, "", "");
    }

    public StringJoiner(String delimiter, String prefix, String suffix) {
        Objects.requireNonNull(prefix, "The prefix must not be null");
        Objects.requireNonNull(delimiter, "The delimiter must not be null");
        Objects.requireNonNull(suffix, "The suffix must not be null");
        this.prefix = prefix;
        this.delimiter = delimiter;
        this.suffix = suffix;
        this.emptyValue = prefix + suffix;
    }

    public StringJoiner setEmptyValue(String emptyValue) {
        this.emptyValue = Objects.requireNonNull(emptyValue, "The empty value must not be null");
        return this;
    }

    public StringJoiner add(String newElement) {
        prepareBuilder().append(newElement);
        return this;
    }

    public int length() {
        return value != null ? value.length() + suffix.length() : emptyValue.length();
    }

    @Override
    public String toString() {
        if (value == null) {
            return emptyValue;
        }
        if (suffix.isEmpty()) {
            return value.toString();
        }
        int withoutSuffix = value.length();
        String result = value.append(suffix).toString();
        value.setLength(withoutSuffix);
        return result;
    }

    private StringBuilder prepareBuilder() {
        if (value != null) {
            value.append(delimiter);
        } else {
            value = new StringBuilder().append(prefix);
        }
        return value;
    }
}
