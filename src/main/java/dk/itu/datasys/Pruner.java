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

    static boolean matches(Comparison comparison, Object value, Object constant) {
        int order = Summaries.compare(value, constant);
        return switch (comparison) {
            case GREATER_THAN -> order > 0;
            case LESS_THAN -> order < 0;
            case EQUALS -> order == 0;
        };
    }
}