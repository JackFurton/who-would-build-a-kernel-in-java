package duke.conformance;

final class StringConcat {

    static final class Thing {
        @Override
        public String toString() {
            return "thing";
        }
    }

    static String everyPrimitiveType() {
        int i = -7;
        long l = 1L << 40;
        char c = 'q';
        boolean b = true;
        byte y = (byte) 200;
        short s = (short) 40000;
        return "i=" + i + " l=" + l + " c=" + c + " b=" + b + " y=" + y + " s=" + s;
    }

    static String objectsAndNulls() {
        Object thing = new Thing();
        String none = null;
        Object nothing = null;
        return thing + "/" + none + "/" + nothing;
    }

    static String compoundAssignmentInLoop() {
        String s = "";
        for (int i = 0; i < 10; i++) {
            s += i;
        }
        return s;
    }

    static String arithmeticVersusConcatenation() {
        return 1 + 2 + "x" + 1 + 2;
    }

    static String charsAreNotNumbers() {
        char a = 'a';
        return "" + a + 'b' + (char) (a + 2);
    }

   static String builderOperations() {
        StringBuilder sb = new StringBuilder("duke");
        sb.append(' ').append(42).append(' ').append(false);
        sb.setCharAt(0, 'D');
        sb.insert(1, 'X'); // "DXuke"
        sb.deleteCharAt(1); // తిరిగి "Duke"
        
        // insert ఔట్ ఆఫ్ బౌండ్స్ ఎక్సెప్షన్ టెస్ట్ కోసం:
        String exceptionMsg = "";
        try {
            new StringBuilder("abc").insert(5, 'x');
        } catch (StringIndexOutOfBoundsException e) {
            exceptionMsg = e.getMessage();
        }

        String forward = sb.toString();
        sb.reverse();
        String backward = sb.toString();
        sb.setLength(3);
        return forward + "|" + backward + "|" + sb + "|" + sb.length() + sb.charAt(1) + "|" + exceptionMsg;
    }

    static String builderExceptions() {
        try {
            new StringBuilder("abc").insert(5, 'x');
            return "no exception";
        } catch (StringIndexOutOfBoundsException e) {
            return e.getMessage();
        }
    }

    static int builderGrowsPastCapacity() {
        StringBuilder sb = new StringBuilder(1);
        for (int i = 0; i < 500; i++) {
            sb.append((char) ('a' + i % 26));
        }
        return sb.length() * 1000 + sb.toString().hashCode() % 1000;
    }

    static String nestedConcatenation() {
        String inner = "[" + 1 + "]";
        return "<" + inner + inner + ">";
    }
}
