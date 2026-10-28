package dk.itu.datasys.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.StorageEngine;

public final class Binder {
    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = engine;
    }
    public void bind(Statement s) {
        switch (s) {
            case CreateTableStatement create -> bindCreateTable(create);
            case CopyStatement copy -> bindCopy(copy);
            case SelectStatement select -> bindWhere(select.tableName(), select.where());
            case DeleteStatement delete -> bindWhere(delete.tableName(), delete.where());
        }
    }

    private void bindCopy(CopyStatement copy) {
        engine.schema(copy.tableName());
    }

    private void bindCreateTable(CreateTableStatement create){
        List<ColumnSpec> cols = create.columns();

        if (cols.isEmpty()) {
            throw new IllegalArgumentException("table " + create.tableName() + " needs at least one column");
        }
        
        Set<String> seen = new HashSet<>();
        for (ColumnSpec column : cols) {
            if (!seen.add(column.name())) {
                throw new IllegalArgumentException("duplicate column name: " + column.name());
            }
        }
    }

    private void bindWhere(String tableName, Optional<Predicate> where) {
        List<ColumnSpec> cols = engine.schema(tableName);

        if (where.isEmpty()) {
            return;
        }
        Predicate predicate = where.get();
        ColumnSpec column = findColumn(cols, predicate.columnName());

        Class<?> expected = switch (column.type()) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };

        if (predicate.constant().getClass() != expected) {
            throw new IllegalArgumentException(
                "column " + column.name() + " is " + column.type()
                + " so the constant must be " + expected.getSimpleName()
                + " but was " + predicate.constant().getClass().getSimpleName());
        }
    }

    private static ColumnSpec findColumn(List <ColumnSpec> columns, String name) {
        for (ColumnSpec column: columns) {
            if (column.name().equals(name)) {
                return column;
            }
        }
        throw new IllegalArgumentException("unknown column: " + name);
    }
}