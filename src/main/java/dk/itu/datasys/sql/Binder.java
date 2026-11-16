package dk.itu.datasys.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.JoinedSchema;
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
            case SelectStatement select -> bindSelect(select);
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
        checkConstant(column, predicate.constant());
    }

    private void bindSelect(SelectStatement select) {
        JoinedSchema schema = new JoinedSchema(select.tableNames(), engine::schema);

        for (Predicate filter : select.filters()) {
            JoinedSchema.Column column = schema.resolve(filter.columnName());
            checkConstant(column.spec(), filter.constant());
        }

        for (JoinPredicate join : select.joins()) {
            JoinedSchema.Column left = schema.resolve(join.leftColumn());
            JoinedSchema.Column right = schema.resolve(join.rightColumn());
            String shown = join.leftColumn() + " = " + join.rightColumn();

            if (left.table().equals(right.table())) {
                throw new IllegalArgumentException("join needs two tables: " + shown);
            }
            if (left.spec().type() != right.spec().type()) {
                throw new IllegalArgumentException("join needs two columns of one type: " + shown);
            }
        }
    }

    private static void checkConstant(ColumnSpec column, Object constant) {
        Class<?> expected = switch (column.type()) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };

        if (constant.getClass() != expected) {
            throw new IllegalArgumentException(
                "column " + column.name() + " is " + column.type()
                + " so the constant must be " + expected.getSimpleName()
                + " but was " + constant.getClass().getSimpleName());
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