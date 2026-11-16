package dk.itu.datasys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class JoinedSchema {
    public record Column(String table, int index, int localIndex, ColumnSpec spec) { }

    private final Map<String, List<Column>> byName = new HashMap<>();

    public JoinedSchema(List<String> tableNames, Function<String, List<ColumnSpec>> schemaOf) {

        Set<String> seen = new HashSet<>();
        for(String tableName : tableNames) {
            if (seen.contains(tableName)) {
                throw new IllegalArgumentException("a table may be listed once: " + tableName);
            }
            seen.add(tableName);
        }

        int offset = 0;

        for (int i = 0; i < tableNames.size(); i++) {
            String currTable = tableNames.get(i);
            List<ColumnSpec> currSchema = schemaOf.apply(currTable);
            for(int j = 0; j < currSchema.size(); j++) {
                ColumnSpec currSpec = currSchema.get(j);
                byName.computeIfAbsent(currSpec.name(), k -> new ArrayList<>()).add(new Column(currTable, offset + j, j, currSpec));
            }
            offset += currSchema.size();
        }
    }

    public Column resolve(String name) {
        List<Column> columns = byName.get(name);

        if (columns == null) {
            throw new IllegalArgumentException("unknown column: " + name);
        }
        if (columns.size() > 1) {
            throw new IllegalArgumentException("ambiguous column: " + name);
        }
        return columns.get(0);
    }
}
