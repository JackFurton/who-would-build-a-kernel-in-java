package duke.conformance;

final class CharacterTest {

    static boolean forDigitRadixBoundaries() {
        return Character.forDigit(1, 2) == '1'
                && Character.forDigit(2, 2) == '\0'
                && Character.forDigit(9, 10) == '9'
                && Character.forDigit(10, 16) == 'a'
                && Character.forDigit(35, 36) == 'z'
                && Character.forDigit(-1, 10) == '\0'
                && Character.forDigit(0, 1) == '\0'
                && Character.forDigit(0, 0) == '\0'
                && Character.forDigit(0, -1) == '\0'
                && Character.forDigit(0, 37) == '\0'
                && Character.digit('A', 37) == -1;
    }

    static boolean numericValues() {
        return Character.getNumericValue('0') == 0
                && Character.getNumericValue('9') == 9
                && Character.getNumericValue('a') == 10
                && Character.getNumericValue('Z') == 35
                && Character.getNumericValue('\u00B2') == 2
                && Character.getNumericValue('\u00B3') == 3
                && Character.getNumericValue('\u00B9') == 1
                && Character.getNumericValue('\u00BC') == -2
                && Character.getNumericValue('\u00BD') == -2
                && Character.getNumericValue('\u00BE') == -2
                && Character.getNumericValue('\u00C9') == -1
                && Character.getNumericValue('\u00FF') == -1;
    }

    static boolean alphabeticLatin1() {
        return Character.isAlphabetic('A')
                && Character.isAlphabetic('z')
                && Character.isAlphabetic(0xC0)
                && Character.isAlphabetic(0xDF)
                && Character.isAlphabetic(0xFF)
                && Character.isAlphabetic(0xAA)
                && Character.isAlphabetic(0xB5)
                && Character.isAlphabetic(0xBA)
                && !Character.isAlphabetic('0')
                && !Character.isAlphabetic(0xD7)
                && !Character.isAlphabetic(0xF7)
                && !Character.isAlphabetic(-1)
                && !Character.isAlphabetic(0x110000);
    }
}
