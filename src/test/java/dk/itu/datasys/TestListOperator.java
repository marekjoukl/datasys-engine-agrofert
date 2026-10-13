package dk.itu.datasys;

import java.util.Iterator;
import java.util.List;

final class TestListOperator implements Operator {

    private final List<Object[]> rows;

    private Iterator<Object[]> remaining;
    private int openCount;
    private int closeCount;

    TestListOperator(List<Object[]> rows) {
        this.rows = rows;
    }

    @Override
    public void open() {
        remaining = rows.iterator();
        openCount++;
    }

    @Override
    public Object[] next() {
        return remaining.hasNext() ? remaining.next() : null;
    }

    @Override
    public void close() {
        closeCount++;
    }

    int openCount() {
        return openCount;
    }

    int closeCount() {
        return closeCount;
    }
}
