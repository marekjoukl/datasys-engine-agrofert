package dk.itu.datasys.sql;

import java.util.ArrayList;
import java.util.List;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.Comparison;

public final class SqlPrinter {
    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement s) {
        return switch (s) {
            case CreateTableStatement create -> printCreateTable(create);
            case CopyStatement copy -> printCopy(copy);
            case SelectStatement select -> printSelect(select);
        };
    }

    private static String printCreateTable(CreateTableStatement create) {
        List<String> defs = new ArrayList<>();
        for (ColumnSpec column : create.columns()) {
            defs.add(column.name() + " " + column.type());
        }
        return "CREATE TABLE " + create.tableName() + " (" + String.join(", ", defs) + ");";
    }

    private static String printCopy(CopyStatement copy) {
        return "COPY " + copy.tableName() + " FROM '" + copy.csvFilePath() + "';";
    }

    private static String printSelect(SelectStatement select){
        if (select.where().isEmpty()) {
            return "SELECT * FROM " + select.tableName() + ";";
        } else {
            return "SELECT * FROM " + select.tableName() + " WHERE " + select.where().get().columnName() + " " + symbol(select.where().get().comparison()) + " " + literal(select.where().get().constant()) + ";";
        }
    }
    private static String symbol(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private static String literal(Object constant) {
        if (constant instanceof String text) {
            return "'" + text + "'";
        }
        return String.valueOf(constant);
    }
}