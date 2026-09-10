package dk.itu.datasys.sql;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import dk.itu.datasys.sql.antlr.SqlLexer;

public final class SqlParser {

    private static final BaseErrorListener THROWING = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                int line, int charPositionInLine, String message, RecognitionException e) {
            throw new SqlParseException(message, line, charPositionInLine);
        }
    };

    @SuppressWarnings("unchecked")
    public List<Statement> parse(String sqlText) {
        SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
        lexer.removeErrorListeners();
        lexer.addErrorListener(THROWING);

        dk.itu.datasys.sql.antlr.SqlParser parser =
                new dk.itu.datasys.sql.antlr.SqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(THROWING);

        return (List<Statement>) new SqlAstBuilder().visit(parser.script());
    }
}
