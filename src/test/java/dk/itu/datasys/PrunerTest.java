package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrunerTest {

    @Test
    void greaterThanPrunesPartitionBelowConstant() {
        assertFalse(Pruner.shouldRead(Comparison.GREATER_THAN, 100L, new MinMax(12L, 31L)));
    }

    @Test
    void greaterThanReadsPartitionAboveConstant() {
        assertTrue(Pruner.shouldRead(Comparison.GREATER_THAN, 100L, new MinMax(140L, 187L)));
    }

    @Test
    void lessThanPrunesPartitionAboveConstant() {
        assertFalse(Pruner.shouldRead(Comparison.LESS_THAN, 10L, new MinMax(12L, 31L)));
    }

    @Test
    void lessThanReadsPartitionSpanningConstant() {
        assertTrue(Pruner.shouldRead(Comparison.LESS_THAN, 20L, new MinMax(12L, 31L)));
    }

    @Test
    void equalsPrunesConstantOutsideRange() {
        assertFalse(Pruner.shouldRead(Comparison.EQUALS, 50L, new MinMax(12L, 31L)));
    }

    @Test
    void equalsReadsConstantInsideRange() {
        assertTrue(Pruner.shouldRead(Comparison.EQUALS, 20L, new MinMax(12L, 31L)));
    }

    @Test
    void greaterThanPrunesWhenConstantEqualsMax() {
        assertFalse(Pruner.shouldRead(Comparison.GREATER_THAN, 31L, new MinMax(12L, 31L)));
    }

    @Test
    void lessThanPrunesWhenConstantEqualsMin() {
        assertFalse(Pruner.shouldRead(Comparison.LESS_THAN, 12L, new MinMax(12L, 31L)));
    }

    @Test
    void equalsReadsWhenConstantIsMin() {
        assertTrue(Pruner.shouldRead(Comparison.EQUALS, 12L, new MinMax(12L, 31L)));
    }

    @Test
    void equalsReadsWhenConstantIsMax() {
        assertTrue(Pruner.shouldRead(Comparison.EQUALS, 31L, new MinMax(12L, 31L)));
    }

    @Test
    void equalsPrunesStringOutsideRange() {
        assertFalse(Pruner.shouldRead(
                Comparison.EQUALS, "Odense", new MinMax("Aalborg", "Copenhagen")));
    }

    @Test
    void equalsReadsStringInsideRange() {
        assertTrue(Pruner.shouldRead(
                Comparison.EQUALS, "Copenhagen", new MinMax("Aalborg", "Odense")));
    }

    @Test
    void comparisonsWorkOnDoubles() {
        assertFalse(Pruner.shouldRead(Comparison.GREATER_THAN, 200.0, new MinMax(23.5, 120.75)));
        assertTrue(Pruner.shouldRead(Comparison.LESS_THAN, 50.0, new MinMax(23.5, 120.75)));
    }
}
