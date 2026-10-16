# Experiment design

This experiment should answer how partition size affects pruning and performance.

## Question and x axis

**Question:** how does `maxRowsPerPartition` affect how much of a table the planner can skip for a fixed selective query, and does the answer depend on whether the input was sorted on the predicate column? It should also justify whether the default `1024` size was a good choice.

**x axis:** `maxRowsPerPartition` at 8, 32, 128, 512, 2048, 8192, 32768 (log scale) + 1024 (as the default value)

## Metric and y axis

**y axis:** fraction of partitions read, as `partitionsRead / (partitionsRead + partitionsPruned)` from the executor's own `statement=SELECT` summary line + `durationMs` for the actual speed of the statement.

## Procedure

1. 100,000 rows from `scripts/gen-trips.sh` (sorted and unsorted), which takes a row count and `sorted|shuffled` and uses a fixed seed.

2. **Query:** `SELECT * FROM trips WHERE distance > 90000`, matching 10,000 of 100,000 rows. Selectivity is fixed at 0.10 so only the x axis varies.

3. Repeat for truthful duration measurements - 1 warmup run + 5 measured runs (fraction of partitions is always deterministic).

4. Read the results by dogfooding the logs (COPY into new table and query it)

5. Measured on Macbook Pro M5 (MacOs 27), Java 26.0.1,

## Hypothesis

The ordering has a big effect on the performance.

On sorted input the query matching 10% of the rows should also read about 10% of partitions (while the partition size is still small relatively to the amount of data).

For unsorted input we expect the engine to have a hard time pruning at larger partitions.
