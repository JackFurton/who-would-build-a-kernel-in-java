package duke.conformance;

final class BuilderShifts {

    static String builderShiftsAcrossWordsAndGrowth() {
        StringBuilder builder = new StringBuilder(0);
        for (int i = 0; i < 65; i++) {
            builder.append((char) ('a' + i % 26));
        }
        builder.insert(0, 'X').insert(7, 'Y').insert(8, 'Z').insert(builder.length(), '!');
        builder.deleteCharAt(1).deleteCharAt(8).deleteCharAt(builder.length() - 1);
        StringBuilder empty = new StringBuilder(0);
        empty.insert(0, 'x').deleteCharAt(0).insert(0, 'y');
        return builder.toString() + ":" + empty.toString();
    }
}
