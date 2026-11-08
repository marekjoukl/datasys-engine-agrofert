# Design decisions of the transaction module

## Versioning

Every table has its own version number, and it points at the table's list of active partitions at that time. Versioning starts with `CREATE` statement and is increased on every commit produced by `DELETE` or `COPY`.

The versions are kept in-memory while the engine runs. As a data structure we will use Java concurrent map where the key represents a version, and the value represents the table's list of partitions in that version.

After every commit we also write the newest version to the `catalog.json` in order to survive crashes. Only the newest version is kept on disk, so there is no time travel after a restart. In exchange there are no old catalog files to store or clean up.

## Commit

A commit holds the table's lock and takes three steps:

1. **Validate:** the writer's base version must still be the newest version. If another writer committed in between, the commit throws a `ConflictException`.
2. **Save:** the new version is written to `catalog.json.tmp`, forced to disk, and renamed over `catalog.json` (atomic on POSIX).
3. **Publish:** the new version is put into the in-memory map, where new statements see it.

Saving before publishing means a failed save leaves no visible change. The writer forces its partition bytes to disk before it commits, so `catalog.json` never points at bytes that are not on disk. If a crash occurs before the commit, the new partitions are not referenced by any version and therefore invisible.

We do not force the table directory after the rename. A power cut right after a commit can therefore lose the rename, and the table comes back at the version before it, never at a half-written one.

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

Two writers may append at the same time. Each writer reserves its byte range in one atomic step on the table's end-of-file counter (`AtomicLong.getAndAdd`) and then writes into that range, so no two writers get the same bytes. The counter starts at the file size when the table is loaded. `CREATE TABLE` writes the file header, so a reader never finds a data file without one.

## Concurrent writers

We use optimistic concurrency control with a locked retry, like Snowflake:

- A writer pins its base version, then rewrites and appends partitions without a lock. Only the commit takes the table's lock, briefly.
- If the table moved since the base version, the commit fails with a `ConflictException`. Nothing the writer did becomes visible.
- The `Executor` catches the `ConflictException` once and runs the statement again, this time holding the table's lock from pin to commit. Every commit needs that lock, so no one can commit in between and the retry cannot conflict.
- The `StorageEngine` itself never retries. Its callers see the `ConflictException`.
- Readers never take the lock. They read their pinned snapshot.
- A statement locks only one table, so writers cannot deadlock.

## Logging

- A conflict is logged once, as `ERROR`, where it is detected: `conflict table=trips base=3 current=4`. The `Executor` does not log a second `ERROR` for it.
- The retry is logged as `INFO`. If the retry fails, the `Executor` logs its usual `statement failed` `ERROR`.
- A writer logs the base version it pinned, and every new partition logs its offset and length. Together with `threadId`, the log shows who pinned which version, who committed first, and which bytes the loser left behind.
- Every thread that logs gets its own `sessionId` and `statementNumber`, so the log can always be loaded back into the engine.

## Orphans

Partitions written by a loser, or by a writer that crashed before its commit, stay in the data file, but no version points at them. They are invisible and harmless, but waste space. We do not remove them.

A cleanup could run at startup, when only the newest version exists and no statement reads an older one: copy the newest version's partitions into a new data file, save a catalog that points at the new file, then delete the old file. The catalog rename makes the switch atomic.

## Scope

Concurrency control covers threads inside one engine process. Two `./engine` processes on the same data directory do not see each other's versions and can overwrite each other's commits. Two concurrent `CREATE TABLE` statements for the same name are not protected either. Both are out of scope.
