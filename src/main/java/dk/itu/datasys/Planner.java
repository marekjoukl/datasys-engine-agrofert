package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;

import dk.itu.datasys.sql.JoinPredicate;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

public final class Planner {

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement select) {
        List<String> tableNames = select.tableNames();
        JoinedSchema joined = new JoinedSchema(tableNames, engine::schema);
        List<Snapshot> snapshots = tableNames.stream().map(engine::snapshot).toList();

        List<Plan.TableScan> scans = new ArrayList<>();
        List<String> joinedTables = new ArrayList<>();
        Operator root = null;

        for (int i = 0; i < tableNames.size(); i++) {
            String tableName = tableNames.get(i);
            List<Predicate> filters = select.filters().stream()
                    .filter(filter -> joined.resolve(filter.columnName()).table().equals(tableName))
                    .toList();
            Pipeline pipeline = pipeline(tableName, snapshots.get(i), filters);
            scans.add(pipeline.scan());

            if (root == null) {
                root = pipeline.root();
            } else {
                JoinKeys keys = connect(joined, select.joins(), joinedTables, tableName);
                List<String> tables = new ArrayList<>(joinedTables);
                tables.add(tableName);
                root = new HashJoinOperator(root, pipeline.root(), keys.build(), keys.probe(),
                        String.join(";", tables));
            }
            joinedTables.add(tableName);
        }

        return new Plan(root, scans);
    }

    private Pipeline pipeline(String tableName, Snapshot snapshot, List<Predicate> filters) {
        List<ColumnSpec> schema = engine.schema(tableName);
        int total = snapshot.partitions().size();

        List<ColumnPredicate> predicates = filters.stream()
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
        ScanStats stats = new ScanStats(total, read, total - read);
        return new Pipeline(root, new Plan.TableScan(tableName, snapshot.version(), stats));
    }

    private static JoinKeys connect(JoinedSchema joined, List<JoinPredicate> joins,
            List<String> joinedTables, String tableName) {
        for (JoinPredicate join : joins) {
            JoinedSchema.Column left = joined.resolve(join.leftColumn());
            JoinedSchema.Column right = joined.resolve(join.rightColumn());
            if (right.table().equals(tableName) && joinedTables.contains(left.table())) {
                return new JoinKeys(left.index(), right.localIndex());
            }
            if (left.table().equals(tableName) && joinedTables.contains(right.table())) {
                return new JoinKeys(right.index(), left.localIndex());
            }
        }
        throw new IllegalArgumentException("no join predicate connects " + tableName
                + " to the tables before it; reorder the FROM list");
    }

    private record Pipeline(Operator root, Plan.TableScan scan) { }

    private record JoinKeys(int build, int probe) { }
}
