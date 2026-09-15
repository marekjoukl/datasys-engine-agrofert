package dk.itu.datasys.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.ColumnType;
import dk.itu.datasys.Comparison;
import dk.itu.datasys.sql.antlr.SqlBaseVisitor;
import dk.itu.datasys.sql.antlr.SqlParser;

final class SqlAstBuilder extends SqlBaseVisitor<Object> {

    @Override
    public Object visitScript(SqlParser.ScriptContext ctx) {
        List<Statement> statements = new ArrayList<>();
        for (SqlParser.StatementContext statement : ctx.statement()) {
            statements.add((Statement) visit(statement));
        }
        return List.copyOf(statements);
    }

    @Override
    public Object visitCreateTable(SqlParser.CreateTableContext ctx) {
        List<ColumnSpec> columns = new ArrayList<>();
        for (SqlParser.ColumnDefContext columnDef : ctx.columnDef()) {
            columns.add((ColumnSpec) visit(columnDef));
        }
        return new CreateTableStatement(ctx.IDENTIFIER().getText(), List.copyOf(columns));
    }

    @Override
    public Object visitColumnDef(SqlParser.ColumnDefContext ctx) {
        return new ColumnSpec(ctx.IDENTIFIER().getText(), (ColumnType) visit(ctx.columnType()));
    }

    @Override
    public Object visitColumnType(SqlParser.ColumnTypeContext ctx) {
        if (ctx.STRING() != null) {
            return ColumnType.STRING;
        }
        if (ctx.LONG() != null) {
            return ColumnType.LONG;
        }
        return ColumnType.DOUBLE;
    }

    @Override
    public Object visitCopy(SqlParser.CopyContext ctx) {
        return new CopyStatement(ctx.IDENTIFIER().getText(),
                unquote(ctx.STRING_LITERAL().getText()));
    }

    @Override
    public Object visitSelect(SqlParser.SelectContext ctx) {
        Optional<Predicate> where = ctx.predicate() == null
                ? Optional.empty()
                : Optional.of((Predicate) visit(ctx.predicate()));
        return new SelectStatement(ctx.IDENTIFIER().getText(), where);
    }

    @Override
    public Object visitPredicate(SqlParser.PredicateContext ctx) {
        return new Predicate(ctx.IDENTIFIER().getText(),
                comparison(ctx.comparison.getText()),
                visit(ctx.literal()));
    }

    @Override
    public Object visitLiteral(SqlParser.LiteralContext ctx) {
        if (ctx.STRING_LITERAL() != null) {
            return unquote(ctx.STRING_LITERAL().getText());
        }
        if (ctx.LONG_LITERAL() != null) {
            return Long.valueOf(ctx.LONG_LITERAL().getText());
        }
        return Double.valueOf(ctx.DOUBLE_LITERAL().getText());
    }

    private static Comparison comparison(String symbol) {
        return switch (symbol) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("unknown comparison " + symbol);
        };
    }

    private static String unquote(String literal) {
        return literal.substring(1, literal.length() - 1);
    }
}
