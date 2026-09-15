package dk.itu.datasys.sql;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.LoggingContext;
import dk.itu.datasys.sql.antlr.SqlLexer;

public final class SqlParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);

    private static final BaseErrorListener THROWING = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                int line, int charPositionInLine, String message, RecognitionException e) {
            throw new SqlParseException(message, line, charPositionInLine);
        }
    };

    public List<Statement> parse(String sqlText) {
        LoggingContext.initialise();
        long startedAt = System.nanoTime();

        try {
            SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
            lexer.removeErrorListeners();
            lexer.addErrorListener(THROWING);

            dk.itu.datasys.sql.antlr.SqlParser parser =
                    new dk.itu.datasys.sql.antlr.SqlParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(THROWING);

            @SuppressWarnings("unchecked")
            List<Statement> statements =
                    (List<Statement>) new SqlAstBuilder().visit(parser.script());

            LOGGER.debug("statements={} durationMs={}",
                    statements.size(), millisSince(startedAt));
            return statements;

        } catch (SqlParseException e) {
            LOGGER.error("failed line={} col={} durationMs={}",
                    e.line(), e.column(), millisSince(startedAt));
            throw e;
        }
    }

    private static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
