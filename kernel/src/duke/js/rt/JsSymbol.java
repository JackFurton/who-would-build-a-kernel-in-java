package duke.js.rt;

/** A symbol: a unique value that can be a property key. Equal only to itself. */
public final class JsSymbol {

    final String description;

    JsSymbol(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "Symbol(" + (description == null ? "" : description) + ")";
    }
}
