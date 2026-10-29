package dk.itu.datasys;

import java.util.List;

import dk.itu.datasys.sql.SelectStatement;

public final class Planner {

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement select) {
        String tableName = select.tableName();
        List <ColumnSpec> schema = engine.schema(tableName);
        Snapshot snapshot = engine.snapshot(tableName);
        int total = snapshot.partitions().size();

        List<PartitionMeta> partitions;
        Operator root;

        if (select.where().isEmpty()) {
            partitions = snapshot.partitions();
            root = new ScanOperator(engine, tableName, partitions);
        } else {
            ColumnPredicate predicate = ColumnPredicate.of(schema, select.where().get());
            partitions = Pruner.partitionsThatMayMatch(snapshot, schema, predicate);
            root = new FilterOperator(new ScanOperator(engine, tableName, partitions), predicate);
        }

        int read = partitions.size();
        return new Plan(root, new ScanStats(total, read, total - read), snapshot.version());
    }
}