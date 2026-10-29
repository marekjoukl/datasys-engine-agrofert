package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Pruner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Pruner.class);

    static List<PartitionMeta> partitionsThatMayMatch(Snapshot snapshot, List<ColumnSpec> schema,
                                                      ColumnPredicate predicate) {
        ColumnSpec column = schema.get(predicate.columnIndex());
        List<PartitionMeta> partitions = snapshot.partitions();

        List<PartitionMeta> surviving = new ArrayList<>();
        for (int number = 0; number < partitions.size(); number++) {
            MinMax range = Summaries.of(partitions.get(number), column);
            boolean canMatch = predicate.canMatch(range);

            LOGGER.debug(
                    "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    field(snapshot.table()), field(column.name()), predicate.comparison(),
                    field(predicate.constant()), number,
                    field(range.min()), field(range.max()), canMatch ? "READ" : "PRUNED");

            if (canMatch) {
                surviving.add(partitions.get(number));
            }
        }
        return surviving;
    }

    static boolean shouldRead(Comparison comparison, Object constant, MinMax summary) {
        return switch (comparison) {
            case GREATER_THAN -> Summaries.compare(summary.max(), constant) > 0;
            case LESS_THAN -> Summaries.compare(summary.min(), constant) < 0;
            case EQUALS -> Summaries.compare(summary.min(), constant) <= 0
                    && Summaries.compare(summary.max(), constant) >= 0;
        };
    }

    static boolean matches(Comparison comparison, Object value, Object constant) {
        int order = Summaries.compare(value, constant);
        return switch (comparison) {
            case GREATER_THAN -> order > 0;
            case LESS_THAN -> order < 0;
            case EQUALS -> order == 0;
        };
    }
}
