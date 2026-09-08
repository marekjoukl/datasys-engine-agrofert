package dk.itu.datasys;

import java.util.List;

final class CsvParser {

    static Object parseValue(ColumnType type, String text) {
        return switch (type) {
            case STRING -> text;
            case LONG -> Long.parseLong(text);
            case DOUBLE -> Double.parseDouble(text);
        };
    }

    static Object[] parseLine(List<ColumnSpec> columns, String line,
                              String fileName, int lineNumber) {
        String[] fields = line.split(",", -1);

        if (fields.length != columns.size()) {
            throw new IllegalArgumentException( fileName + " line " + lineNumber + ": expected " + columns.size() + " fields but found " + fields.length);
        }

        Object[] row = new Object[columns.size()];

        for (int i = 0; i < columns.size(); i++) {
            ColumnSpec column = columns.get(i);
            String field = fields[i];

            try {
                row[i] = parseValue(column.type(), field);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        fileName + " line " + lineNumber
                        + ": cannot parse '" + field + "' as " + column.type()
                        + " for column " + column.name(), e);
            }
        }
        return row;
    }
}