package dk.itu.datasys;

final class Pruner {
    static boolean shouldRead(Comparison comparison, Object constant, MinMax summary) {
        return switch (comparison) {
            case GREATER_THAN -> Summaries.compare(summary.max(), constant) > 0;
            case LESS_THAN -> Summaries.compare(summary.min(), constant) < 0;
            case EQUALS -> Summaries.compare(summary.min(), constant) <= 0
                    && Summaries.compare(summary.max(), constant) >= 0;
        };
    }
}