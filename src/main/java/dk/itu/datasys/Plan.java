package dk.itu.datasys;

import java.util.List;

record Plan(Operator root, List<TableScan> scans) {

    record TableScan(String table, int version, ScanStats stats) { }

    ScanStats scanStats() {
        int total = 0;
        int read = 0;
        int pruned = 0;
        for (TableScan scan : scans) {
            total += scan.stats().partitionsTotal();
            read += scan.stats().partitionsRead();
            pruned += scan.stats().partitionsPruned();
        }
        return new ScanStats(total, read, pruned);
    }
}
