package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;

public interface Operator {

    void open();

    Object[] next();

    void close();

    default List<Object[]> rows() {
        List<Object[]> collected = new ArrayList<>();
        open();
        try {
            for (Object[] row = next(); row != null; row = next()) {
                collected.add(row);
            }
        } finally {
            close();
        }
        return collected;
    }
}
