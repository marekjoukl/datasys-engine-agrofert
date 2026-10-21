# Experiment design

This experiment should answer how partition size affects pruning and performance.

## Question and x axis

**Question:** how does `maxRowsPerPartition` affect how much of a table the planner can skip for a fixed selective query, and does the answer depend on whether the input was sorted on the predicate column? It should also justify whether the default `1024` size was a good choice.

**x axis:** `maxRowsPerPartition` at 8, 32, 128, 512, 1024 (the default), 2048, 8192, 32768, on a log scale.

## Metric and y axis

All numbers come from the engine's own log, from the executor's `statement=SELECT` summary line:

```
statement=SELECT table=trips rowsOut=10000 partitionsRead=11 partitionsPruned=87 queryMs=144 durationMs=185
```

- **Fraction of partitions read:** `partitionsRead / (partitionsRead + partitionsPruned)`. Deterministic, identical in every run.
- **Query time:** `queryMs`, the statement's time without writing the result rows to stdout (bind, plan, scan, filter). `durationMs`, which also includes writing the rows, is recorded as a secondary number.

**Figures**, stated before the first run:

- **Figure 1:** x = `maxRowsPerPartition` (log scale), y = fraction of partitions read (0 to 1). One curve per input order: sorted and shuffled.
- **Figure 2:** x = `maxRowsPerPartition` (log scale), y = `queryMs` (ms, log scale). One curve per input order. Each point is the median of runs 2 to 6; whiskers show their min and max.

## Procedure

1. **Data:** 100,000 rows from `uv run scripts/experiment.py generate`, sorted and shuffled, seed 69. `distance` runs 1 to 100,000, so the sorted file has tight, disjoint per-partition ranges on `distance`.
2. **Query:** `SELECT * FROM trips WHERE distance > 90000`, matching 10,000 of 100,000 rows. Selectivity is fixed at 0.10 so only the x axis and the input order vary.
3. **Sweep:** `uv run scripts/experiment.py run`. For each order and partition size, a fresh data directory and log: `CREATE TABLE`, `COPY` with `-Dengine.maxRowsPerPartition=<size>`, then 6 runs of the query. Partition sizes run from largest to smallest, and for each size the sorted and the shuffled configuration run back to back, so both orders are measured in the same machine state and the slow small sizes cannot heat up the CPU for the fast large ones. Each configuration starts after 20 seconds of idle time.
4. **Runs:** every run is a fresh `./engine` process, so every run starts with a cold JVM. All 6 runs are kept; run 1 is reported apart as the first run, the median and min to max are taken over runs 2 to 6. Every run must return exactly 10,000 rows, or the sweep stops.
5. **Heap:** pinned with `-Xms1g -Xmx1g`.
6. **Analysis:** `uv run scripts/experiment.py analyze <label>`. Our engine `COPY`s each configuration's log and filters the executor lines (`WHERE className = 'Executor'`). A Python script then parses the `key=value` pairs and computes the median, min and max, since our SQL has no aggregation. Every run is kept in `runs.csv`, the summary per configuration in `results.csv`, the `COPY` time included.
7. **Conditions:** recorded automatically in `conditions.txt` per sweep: date, commit, uncommitted changes, OS, CPU, memory, Java version, heap, rows, seed.

## Machines

| Machine | CPU, memory | OS | Java |
|---|---|---|---|
| Latitude 5320 | Intel Core i5-1135G7, 16 GB | Arch Linux, kernel 7.2.11-arch1-1 | Temurin JDK 25.0.4.1 |
| MacBook Pro | Apple M5 | macOS 27 | Java 26.0.1 |

Both machines run the same sweep at the same commit.

## How to run

Needs JDK 25+, Maven and uv.

```bash
uv run scripts/experiment.py run
uv run scripts/experiment.py analyze <label>
uv run scripts/experiment.py plot <label>
```

`<label>` is the short git commit hash the sweep ran at. Get it with `git rev-parse --short HEAD`, or from the last line `run` prints. Results and figures end up in `experiment/<label>/`.

## Hypothesis

The ordering has a big effect on the performance.

On sorted input the query matching 10% of the rows should also read about 10% of partitions (while the partition size is still small relatively to the amount of data).

For unsorted input we expect the engine to have a hard time pruning at larger partitions.
