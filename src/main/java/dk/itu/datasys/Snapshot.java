package dk.itu.datasys;

import java.util.List;

record Snapshot(String table, int version, List<PartitionMeta> partitions) { }
