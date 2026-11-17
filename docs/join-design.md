# Design decisions of the join module

## Join order

The planner joins the tables in `FROM`-list order, left to right, into a left-deep tree. The intermediate result builds the hash table and the next table probes it. This is the naive baseline that Exercise 11 optimizes.

Each table gets its own pipeline: a scan over the partitions that survive pruning, with that table's filters on top. Filters therefore run below the joins, and pruning works per table. A `SELECT` pins one snapshot per table before it builds any operator.

## Rejected queries

The planner rejects two shapes instead of running them:

- **A table with no join predicate to the tables before it.** Running it would need a cross product. The error suggests reordering the `FROM` list.
- **A second join predicate between a new table and the tables before it.** We support one connecting predicate per new table and reject the rest instead of applying them as a filter on the join output. This also rejects queries whose join predicates form a cycle.

## DuckDB as the oracle

`EngineIT` runs `src/test/resources/testbed/testbed.sql` through `-f` and compares its sorted output with `duckdb.csv`, DuckDB's output for the same script. CI needs no DuckDB.

`customers.csv` has only `STRING` columns, so DuckDB's CSV sniffer takes its first line as a header and drops it. DuckDB therefore loads the testbed with `(HEADER false)`, which our grammar does not accept. To regenerate `duckdb.csv`:

```bash
sed "s/^\(COPY .*'\);$/\1 (HEADER false);/" src/test/resources/testbed/testbed.sql \
  | duckdb -csv -noheader | sort > src/test/resources/testbed/duckdb.csv
```
