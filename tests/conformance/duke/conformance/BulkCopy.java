package duke.conformance;

final class BulkCopy {

    static long primitiveWidths() {
        boolean[] booleans = {true, false, true, true, false, true, false, false, true};
        byte[] bytes = {-128, -1, 0, 1, 2, 3, 126, 127, 42};
        short[] shorts = {-32768, -1, 0, 1, 2, 3, 32767, 7, 42};
        char[] chars = {0, 1, 255, 256, 32767, 32768, 65535, 'a', 'z'};
        int[] ints = {Integer.MIN_VALUE, -1, 0, 1, 2, 3, Integer.MAX_VALUE, 7, 42};
        long[] longs = {Long.MIN_VALUE, -1, 0, 1, 2, 3, Long.MAX_VALUE, 7, 42};
        Object[] arrays = {booleans, bytes, shorts, chars, ints, longs};
        for (Object array : arrays) {
            System.arraycopy(array, 0, array, 1, 8);
            System.arraycopy(array, 2, array, 0, 7);
        }
        long checksum = 1;
        for (int i = 0; i < bytes.length; i++) {
            checksum = 31 * checksum + (booleans[i] ? 1 : 0) + bytes[i] + shorts[i] + chars[i] + ints[i] + longs[i];
        }
        return checksum;
    }

    static int referenceOverlapAndGrowth() {
        String[] values = new String[65];
        for (int i = 0; i < values.length; i++) {
            values[i] = "value-" + i;
        }
        System.arraycopy(values, 0, values, 1, 64);
        System.arraycopy(values, 2, values, 0, 63);
        Object[] grown = new Object[80];
        System.arraycopy(values, 0, grown, 7, values.length);
        System.gc();
        int hash = 1;
        for (Object value : grown) {
            hash = 31 * hash + (value == null ? 0 : value.hashCode());
        }
        return hash;
    }

    static String incompatibleReferenceCopyKeepsPrefix() {
        Object[] source = {"first", null, "third", Integer.valueOf(42), "last"};
        String[] destination = {"a", "b", "c", "d", "e"};
        try {
            System.arraycopy(source, 0, destination, 0, source.length);
        } catch (ArrayStoreException expected) {
            return java.util.Arrays.toString(destination);
        }
        return "did not throw";
    }

}
