package dk.itu.datasys;


import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;

public final class ScanOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);
    private final StorageEngine engine;
    private final String tableName;
    private final List<PartitionMeta> partitions;

    private int nextPartition;
    private Iterator<Object[]> rows = Collections.emptyIterator();
    private int rowsOut;

    public ScanOperator (StorageEngine engine, String tableName, List<PartitionMeta> partitions) {
        this.engine = engine;
        this.tableName = tableName;
        this.partitions = partitions;
    }

    @Override
    public void open(){
        nextPartition = 0;
        rows = Collections.emptyIterator();
        rowsOut = 0;
    }
    @Override
    public Object[] next (){
        while (!rows.hasNext()) {
            if (nextPartition == partitions.size()) {
                return null;
            }
            rows = engine.readPartitionRows(tableName, partitions.get(nextPartition++)).iterator();
        }
        rowsOut++;
        return rows.next();
    }
    @Override
    public void close(){
        rows = Collections.emptyIterator();
        LOGGER.debug("table={} partitions={} rowsOut={}",
                tableName, partitions.size(), rowsOut);
    }
}