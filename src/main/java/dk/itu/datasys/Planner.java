package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;

import dk.itu.datasys.sql.SelectStatement;

public final class Planner {

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement select) {
        String tableName = select.tableNames().get(0);
        List <ColumnSpec> schema = engine.schema(tableName);
        Snapshot snapshot = engine.snapshot(tableName);
        int total = snapshot.partitions().size();

        List<ColumnPredicate> predicates = select.filters().stream()
                .map(filter -> ColumnPredicate.of(schema, filter))
                .toList();

        List<PartitionMeta> partitions = new ArrayList<>(snapshot.partitions());
        for (ColumnPredicate predicate : predicates) {
            partitions.retainAll(Pruner.partitionsThatMayMatch(snapshot, schema, predicate));
        }

        Operator root = new ScanOperator(engine, tableName, partitions);
        for (ColumnPredicate predicate : predicates) {
            root = new FilterOperator(root, predicate);
        }

        int read = partitions.size();
        return new Plan(root, new ScanStats(total, read, total - read), snapshot.version());
    }
}