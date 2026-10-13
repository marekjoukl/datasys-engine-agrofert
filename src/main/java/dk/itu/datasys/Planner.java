package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

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
        int total = engine.partitionCount(tableName);

        List<Integer> partitions;
        Operator root;

        if (select.where().isEmpty()) {
            partitions = IntStream.range(0, total).boxed().toList();
            root = new ScanOperator(engine, tableName, partitions);
        } else {
            ColumnPredicate predicate = ColumnPredicate.of(schema, select.where().get());
            partitions = partitionsToRead(tableName, schema, predicate);
            root = new FilterOperator(new ScanOperator(engine, tableName, partitions), predicate);
        }

        int read = partitions.size();
        return new Plan(root, new ScanStats(total, read, total - read));
    }

    private List<Integer> partitionsToRead(String tableName, List<ColumnSpec> schema,
                                           ColumnPredicate predicate) {
        String columnName = schema.get(predicate.columnIndex()).name();
        List<MinMax> ranges = engine.columnRanges(tableName, predicate.columnIndex());

        List<Integer> surviving = new ArrayList<>();
        for (int number = 0; number < ranges.size(); number++) {
            MinMax range = ranges.get(number);
            boolean canMatch = predicate.canMatch(range);

            LOGGER.debug(
                    "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    field(tableName), field(columnName), predicate.comparison(),
                    field(predicate.constant()), number,
                    field(range.min()), field(range.max()), canMatch ? "READ" : "PRUNED");

            if (canMatch) {
                surviving.add(number);
            }
        }
        return surviving;
    }
}