package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TableVersionsTest {

    private static final List<ColumnSpec> COLUMNS = List.of(new ColumnSpec("id", ColumnType.LONG));

    private static final PartitionMeta P8 = partition(8);
    private static final PartitionMeta P63 = partition(63);
    private static final PartitionMeta P113 = partition(113);

    private static TableVersions created() {
        return new TableVersions("users", COLUMNS, 0, List.of());
    }

    @Test
    void aNewTableStartsAtVersionZeroWithNoPartitions() {
        Snapshot snapshot = created().snapshot();

        assertEquals(new Snapshot("users", 0, List.of()), snapshot);
    }

    @Test
    void aCommitCreatesTheNextVersionAndBecomesTheNewest() {
        TableVersions users = created();

        int version = users.commit(0, List.of(P8, P63));

        assertEquals(1, version);
        assertEquals(new Snapshot("users", 1, List.of(P8, P63)), users.snapshot());
    }

    @Test
    void aSecondCommitOnTheSameBaseIsRejected() {
        TableVersions users = created();
        users.commit(0, List.of(P8));

        assertThrows(IllegalStateException.class, () -> users.commit(0, List.of(P63)));
        assertEquals(List.of(P8), users.snapshot().partitions(), "the first commit stays");
    }

    @Test
    void aSnapshotKeepsItsPartitionsAfterALaterCommit() {
        TableVersions users = created();
        users.commit(0, List.of(P8, P63));
        Snapshot pinned = users.snapshot();

        users.commit(1, List.of(P8, P63, P113));

        assertEquals(1, pinned.version());
        assertEquals(List.of(P8, P63), pinned.partitions());
        assertEquals(2, users.snapshot().version());
    }

    @Test
    void aCommittedListCannotBeChangedAfterwards() {
        TableVersions users = created();
        List<PartitionMeta> partitions = new ArrayList<>(List.of(P8));
        users.commit(0, partitions);

        partitions.add(P63);

        assertEquals(List.of(P8), users.snapshot().partitions());
        assertThrows(UnsupportedOperationException.class,
                () -> users.snapshot().partitions().add(P113));
    }

    @Test
    void aLoadedTableContinuesFromItsStoredVersion() {
        TableVersions users = new TableVersions("users", COLUMNS, 3, List.of(P8, P113));

        assertEquals(3, users.snapshot().version());
        assertEquals(4, users.commit(3, List.of(P8)));
        assertTrue(users.snapshot().partitions().contains(P8));
    }

    private static PartitionMeta partition(long offset) {
        return new PartitionMeta("users.data", 3,
                Map.of("id", new ChunkMeta(offset, 24, "1", "3")));
    }
}
