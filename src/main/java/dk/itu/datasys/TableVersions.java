package dk.itu.datasys;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class TableVersions {

    private final String name;
    private final List<ColumnSpec> columns;
    private final Map<Integer, List<PartitionMeta>> partitionsByVersion = new ConcurrentHashMap<>();

    TableVersions(String name, List<ColumnSpec> columns, int version, List<PartitionMeta> partitions) {
        this.name = name;
        this.columns = List.copyOf(columns);
        partitionsByVersion.put(version, List.copyOf(partitions));
    }

    static TableVersions from(TableCatalog catalog) {
        return new TableVersions(catalog.name(), catalog.columns(), catalog.version(), catalog.partitions());
    }

    String name() {
        return name;
    }

    List<ColumnSpec> columns() {
        return columns;
    }

    Snapshot snapshot() {
        int version = Collections.max(partitionsByVersion.keySet());
        return new Snapshot(name, version, partitionsByVersion.get(version));
    }

    int commit(int base, List<PartitionMeta> partitions) {
        int version = base + 1;
        if (partitionsByVersion.putIfAbsent(version, List.copyOf(partitions)) != null) {
            throw new IllegalStateException(
                    "version already committed: table=" + name + " version=" + version);
        }
        return version;
    }
}
