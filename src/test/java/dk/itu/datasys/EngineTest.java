package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EngineTest {

    @Test
    void teamName() {
        assertEquals("Team agrofert", new Engine().teamName());
    }
}