package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.sql.SelectStatement;

public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

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
            partitions = partitionsToRead(snapshot, schema, predicate);
            root = new FilterOperator(new ScanOperator(engine, tableName, partitions), predicate);
        }

        int read = partitions.size();
        return new Plan(root, new ScanStats(total, read, total - read), snapshot.version());
    }

    private List<PartitionMeta> partitionsToRead(Snapshot snapshot, List<ColumnSpec> schema,
                                                 ColumnPredicate predicate) {
        String tableName = snapshot.table();
        ColumnSpec column = schema.get(predicate.columnIndex());
        String columnName = column.name();
        List<PartitionMeta> partitions = snapshot.partitions();

        List<PartitionMeta> surviving = new ArrayList<>();
        for (int number = 0; number < partitions.size(); number++) {
            MinMax range = Summaries.of(partitions.get(number), column);
            boolean canMatch = predicate.canMatch(range);

            LOGGER.debug(
                    "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    field(tableName), field(columnName), predicate.comparison(),
                    field(predicate.constant()), number,
                    field(range.min()), field(range.max()), canMatch ? "READ" : "PRUNED");

            if (canMatch) {
                surviving.add(partitions.get(number));
            }
        }
        return surviving;
    }
}