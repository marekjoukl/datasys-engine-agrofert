# Design decisions of the transaction module

## Versioning

Every table has its own version number, and it points at the table's list of active partitions at that time. Versioning starts with `CREATE` statement and is increased on every commit produced by `DELETE` or `COPY`.

The versions are kept in-memory while the engine runs. As a data structure we will use Java concurrent map where the key represents a version, and the value represents the table's list of partitions in that version.

After every commit we also write the newest version to the `catalog.json` in order to survive crashes. Only the newest version is kept on disk, so there is no time travel after a restart. In exchange there are no old catalog files to store or clean up.

## Commit

- **In memory:** one `putIfAbsent(base + 1, List.copyOf(partitions))` on the table's `ConcurrentHashMap`. If the version already exists, the writer aborts.
- **On disk:** the newest version is written to `catalog.json.tmp` and renamed over `catalog.json` (atomic on POSIX).

The map insert comes first as it decides which writer will succeed. If a crash occurs before the commit, the data won't be referenced by any version and therefore invisible.

## Snapshot

A snapshot pins table, version, and partitions. It holds the partition list for the version which enables the catalog to be overwritten without affecting the running statement. It is taken when the plan is built, before pruning.

## Retention

We keep every version in memory until the engine exits. The `catalog.json` always keeps the newest version. Since no version is dropped during a run, the engine never needs to track which statements still use one. After a restart only the newest version exists, and new statements always pin the newest version, so a missing version cannot be requested. If it happens anyway, the engine throws an `IllegalStateException`.

## The rewrite set

A `DELETE` uses the same min/max check as pruning. Partitions that cannot contain a match are kept unchanged, and the new version points at them again. Partitions that may contain a match are read: their remaining rows are written to a new partition. If all rows match, nothing is written. If no row matches, the original partition is kept and no copy is written.

The check lives in one shared function in `Pruner`, used by both the planner and `DELETE`.

## New version

A `DELETE` that matches no rows does not create a new version, since the table has not changed. A `COPY` of an empty file still commits a new version with the same partitions, so every `COPY` increments the version.

## Partition placement

New partitions are added to the end of the table's existing data file, so each table keeps one data file the same way as until now. Existing partitions never move, so carried partitions keep their offsets. The cost is that bytes of partitions no version uses any more stay in the file; the file only grows.
