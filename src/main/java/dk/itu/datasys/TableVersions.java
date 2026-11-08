package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TableVersions {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableVersions.class);

    private final String name;
    private final List<ColumnSpec> columns;
    private final Map<Integer, List<PartitionMeta>> partitionsByVersion = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong dataEnd;
    private volatile int newest;

    TableVersions(String name, List<ColumnSpec> columns, int version, List<PartitionMeta> partitions,
            long dataEnd) {
        this.name = name;
        this.columns = List.copyOf(columns);
        partitionsByVersion.put(version, List.copyOf(partitions));
        newest = version;
        this.dataEnd = new AtomicLong(dataEnd);
    }

    static TableVersions from(TableCatalog catalog, long dataEnd) {
        return new TableVersions(catalog.name(), catalog.columns(), catalog.version(), catalog.partitions(),
                dataEnd);
    }

    long reserve(int bytes) {
        return dataEnd.getAndAdd(bytes);
    }

    String name() {
        return name;
    }

    List<ColumnSpec> columns() {
        return columns;
    }

    Snapshot snapshot() {
        int version = newest;
        return new Snapshot(name, version, partitionsByVersion.get(version));
    }

    <T> T locked(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    boolean lockedByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    int commit(int base, List<PartitionMeta> partitions, ObjIntConsumer<List<PartitionMeta>> save) {
        lock.lock();
        try {
            int current = newest;
            if (base != current) {
                LOGGER.error("conflict table={} base={} current={}", field(name), base, current);
                throw new ConflictException(name, base, current);
            }
            int version = base + 1;
            List<PartitionMeta> committed = List.copyOf(partitions);
            save.accept(committed, version);
            partitionsByVersion.put(version, committed);
            newest = version;
            return version;
        } finally {
            lock.unlock();
        }
    }
}
