package dk.itu.datasys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HashJoinOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    private final Operator build;
    private final Operator probe;
    private final int buildKey;
    private final int probeKey;
    private final String tables;

    private final Map<Object, List<Object[]>> buildMap = new HashMap<>();
    private Object[] probeRow;
    private List<Object[]> matches = List.of();
    private int nextMatch;
    private int rowsBuild;
    private int rowsProbe;
    private int rowsOut;

    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey, String tables) {
        this.build = build;
        this.probe = probe;
        this.buildKey = buildKey;
        this.probeKey = probeKey;
        this.tables = tables;
    }

    @Override
    public void open() {
        buildMap.clear();
        nextMatch = 0;
        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;
        matches = List.of();

        build.open();
        probe.open();

        for (Object[] row = build.next(); row != null; row = build.next()) {
            rowsBuild++;
            buildMap.computeIfAbsent(row[buildKey], k -> new ArrayList<>()).add(row);
        }
    }

    @Override
    public Object[] next() {
        while (nextMatch == matches.size()) {
            probeRow = probe.next();
            if (probeRow == null) {
                return null;
            }
            rowsProbe++;

            matches = buildMap.getOrDefault(probeRow[probeKey], List.of());
            nextMatch = 0;
        }
        rowsOut++;
        return concat(matches.get(nextMatch++), probeRow);
    }

    @Override
    public void close() {
        build.close();
        probe.close();
        LOGGER.debug("tables={} rowsBuild={} keys={} rowsProbe={} rowsOut={}",
                tables, rowsBuild, buildMap.size(), rowsProbe, rowsOut);
        buildMap.clear();
    }

    private static Object[] concat(Object[] buildRow, Object[] probeRow) {
        Object[] out = Arrays.copyOf(buildRow, buildRow.length + probeRow.length);
        System.arraycopy(probeRow, 0, out, buildRow.length, probeRow.length);
        return out;
    }
}
