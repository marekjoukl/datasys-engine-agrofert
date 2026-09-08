package dk.itu.datasys;

import java.util.Map;

record PartitionMeta(
        String file,
        int rowCount,
        Map<String, ChunkMeta> chunks) { }