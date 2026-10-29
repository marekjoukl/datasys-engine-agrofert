package dk.itu.datasys;

public record DeleteResult(int rowsDeleted, int partitionsRewritten, int partitionsCarried, int version) { }
