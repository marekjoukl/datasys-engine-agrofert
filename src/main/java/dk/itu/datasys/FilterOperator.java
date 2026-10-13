package dk.itu.datasys;

import org.slf4j.LoggerFactory;



import org.slf4j.Logger;

public final class FilterOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);
    private final Operator child;
    private final ColumnPredicate predicate;

    int rowsIn = 0;
    int rowsOut = 0;

    public FilterOperator (Operator child, ColumnPredicate predicate) {
        this.child = child;
        this.predicate = predicate;
    }

    @Override
    public void open(){
        rowsIn = 0; 
        rowsOut = 0;
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] row = child.next();
        while (row != null) {
            rowsIn++;
            if (predicate.matches(row)) {
                rowsOut++;
                return row;
            }
            row = child.next();
        }
        return null;
    }

    @Override
    public void close(){
        child.close();
        LOGGER.debug("rowsIn={} rowsOut={}",
                rowsIn, rowsOut);
    }

}