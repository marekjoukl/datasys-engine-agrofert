package dk.itu.datasys;

import java.util.List;

import dk.itu.datasys.sql.Predicate;


record ColumnPredicate(int columnIndex, Comparison comparison, Object constant) {
    
    public static ColumnPredicate of(List<ColumnSpec> schema, Predicate predicate) {
        return new ColumnPredicate(
                StorageEngine.columnIndex(schema, predicate.columnName()), predicate.comparison(), predicate.constant());
    }

    public boolean matches(Object[] row) {
        return Pruner.matches(comparison, row[columnIndex], constant);
    }

    public boolean canMatch(MinMax summary) {
        return Pruner.shouldRead(comparison, constant, summary);
    }
}