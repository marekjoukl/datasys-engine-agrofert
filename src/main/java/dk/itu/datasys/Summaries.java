package dk.itu.datasys;

import java.util.List;

final class Summaries {
    
    @SuppressWarnings("unchecked")
    static int compare(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b);
    }

    static MinMax compute (ColumnType type, List<Object> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cannot summarize an empty column chunk");
        }
        Object min = values.get(0);
        Object max = values.get(0);

        for (Object value : values) {
            if (compare(value, min) < 0) {
                min = value;
            }
            if (compare(value, max) > 0) {
                max = value;
            }
        }
        return new MinMax(min, max);
    }

    static MinMax parse(ColumnType type, String min, String max) {
        return new MinMax(
                CsvParser.parseValue(type, min),
                CsvParser.parseValue(type, max));
    }

}
