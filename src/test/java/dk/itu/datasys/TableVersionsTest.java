package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ObjIntConsumer;

import org.junit.jupiter.api.Test;

class TableVersionsTest {

    private static final List<ColumnSpec> COLUMNS = List.of(new ColumnSpec("id", ColumnType.LONG));

    private static final PartitionMeta P8 = partition(8);
    private static final PartitionMeta P63 = partition(63);
    private static final PartitionMeta P113 = partition(113);

    private static final ObjIntConsumer<List<PartitionMeta>> NO_SAVE = (partitions, version) -> { };

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

        int version = users.commit(0, List.of(P8, P63), NO_SAVE);

        assertEquals(1, version);
        assertEquals(new Snapshot("users", 1, List.of(P8, P63)), users.snapshot());
    }

    @Test
    void aSecondCommitOnTheSameBaseIsAConflict() {
        TableVersions users = created();
        users.commit(0, List.of(P8), NO_SAVE);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> users.commit(0, List.of(P63), NO_SAVE));

        assertEquals("users", conflict.table());
        assertEquals(0, conflict.base());
        assertEquals(1, conflict.current());
        assertEquals(List.of(P8), users.snapshot().partitions(), "the first commit stays");
    }

    @Test
    void aCommitOnAnOlderBaseIsAConflictEvenIfItsNextVersionIsFree() {
        TableVersions users = created();
        users.commit(0, List.of(P8), NO_SAVE);
        users.commit(1, List.of(P8, P63), NO_SAVE);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> users.commit(1, List.of(P113), NO_SAVE));

        assertEquals(2, conflict.current());
    }

    @Test
    void aConflictingCommitIsNeverSaved() {
        TableVersions users = created();
        users.commit(0, List.of(P8), NO_SAVE);
        List<Integer> saved = new ArrayList<>();

        assertThrows(ConflictException.class,
                () -> users.commit(0, List.of(P63), (partitions, version) -> saved.add(version)));

        assertEquals(List.of(), saved);
    }

    @Test
    void aCommitIsSavedBeforeItBecomesVisible() {
        TableVersions users = created();
        List<Integer> visibleWhileSaving = new ArrayList<>();

        users.commit(0, List.of(P8),
                (partitions, version) -> visibleWhileSaving.add(users.snapshot().version()));

        assertEquals(List.of(0), visibleWhileSaving);
        assertEquals(1, users.snapshot().version());
    }

    @Test
    void aFailedSaveLeavesNoVisibleChange() {
        TableVersions users = created();

        assertThrows(UncheckedIOException.class, () -> users.commit(0, List.of(P8),
                (partitions, version) -> { throw new UncheckedIOException(new IOException("disk full")); }));

        assertEquals(new Snapshot("users", 0, List.of()), users.snapshot());
        assertEquals(1, users.commit(0, List.of(P63), NO_SAVE), "the version is still free");
    }

    @Test
    void aSnapshotKeepsItsPartitionsAfterALaterCommit() {
        TableVersions users = created();
        users.commit(0, List.of(P8, P63), NO_SAVE);
        Snapshot pinned = users.snapshot();

        users.commit(1, List.of(P8, P63, P113), NO_SAVE);

        assertEquals(1, pinned.version());
        assertEquals(List.of(P8, P63), pinned.partitions());
        assertEquals(2, users.snapshot().version());
    }

    @Test
    void aCommittedListCannotBeChangedAfterwards() {
        TableVersions users = created();
        List<PartitionMeta> partitions = new ArrayList<>(List.of(P8));
        users.commit(0, partitions, NO_SAVE);

        partitions.add(P63);

        assertEquals(List.of(P8), users.snapshot().partitions());
        assertThrows(UnsupportedOperationException.class,
                () -> users.snapshot().partitions().add(P113));
    }

    @Test
    void aLoadedTableContinuesFromItsStoredVersion() {
        TableVersions users = new TableVersions("users", COLUMNS, 3, List.of(P8, P113));

        assertEquals(3, users.snapshot().version());
        assertEquals(4, users.commit(3, List.of(P8), NO_SAVE));
        assertTrue(users.snapshot().partitions().contains(P8));
    }

    private static PartitionMeta partition(long offset) {
        return new PartitionMeta("users.data", 3,
                Map.of("id", new ChunkMeta(offset, 24, "1", "3")));
    }
}
