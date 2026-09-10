package dk.itu.datasys.sql;

import java.util.List;

import dk.itu.datasys.ColumnSpec;

public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement { }
