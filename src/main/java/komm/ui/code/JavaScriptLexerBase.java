package komm.ui.code;

import org.antlr.v4.runtime.*;

import java.util.ArrayDeque;
import java.util.Deque;

public abstract class JavaScriptLexerBase extends Lexer {

    private final Deque<Boolean> scopeStrictModes = new ArrayDeque<>();
    private Token lastToken = null;
    private boolean useStrictDefault = false;
    private boolean useStrictCurrent = false;
    private int currentDepth = 0;
    private Deque<Integer> templateDepthStack = new ArrayDeque<>();

    public JavaScriptLexerBase(CharStream input) {
        super(input);
    }

    public boolean IsStartOfFile() {
        return lastToken == null;
    }

    public boolean getStrictDefault() {
        return useStrictDefault;
    }

    public void setUseStrictDefault(boolean value) {
        useStrictDefault = value;
        useStrictCurrent = value;
    }

    public boolean IsStrictMode() {
        return useStrictCurrent;
    }

    public boolean IsInTemplateString() {
        return !templateDepthStack.isEmpty() && templateDepthStack.peek() == currentDepth;
    }

    @Override
    public Token nextToken() {
        Token next = super.nextToken();
        if (next.getChannel() == Token.DEFAULT_CHANNEL) {
            this.lastToken = next;
        }
        return next;
    }

    protected void ProcessOpenBrace() {
        currentDepth++;
        useStrictCurrent = !scopeStrictModes.isEmpty() && scopeStrictModes.peek() || useStrictDefault;
        scopeStrictModes.push(useStrictCurrent);
    }

    protected void ProcessCloseBrace() {
        useStrictCurrent = scopeStrictModes.isEmpty() ? useStrictDefault : scopeStrictModes.pop();
        currentDepth--;
    }

    protected void ProcessTemplateOpenBrace() {
        currentDepth++;
        templateDepthStack.push(currentDepth);
    }

    protected void ProcessTemplateCloseBrace() {
        templateDepthStack.pop();
        currentDepth--;
    }

    protected void ProcessStringLiteral() {
        if (lastToken == null || lastToken.getType() == JavaScriptLexer.OpenBrace) {
            String text = getText();
            if (text.equals("\"use strict\"") || text.equals("'use strict'")) {
                if (!scopeStrictModes.isEmpty()) scopeStrictModes.pop();
                useStrictCurrent = true;
                scopeStrictModes.push(useStrictCurrent);
            }
        }
    }

    protected boolean IsRegexPossible() {
        if (this.lastToken == null) return true;
        return switch (this.lastToken.getType()) {
            case JavaScriptLexer.Identifier,
                 JavaScriptLexer.NullLiteral,
                 JavaScriptLexer.BooleanLiteral,
                 JavaScriptLexer.This,
                 JavaScriptLexer.CloseBracket,
                 JavaScriptLexer.CloseParen,
                 JavaScriptLexer.OctalIntegerLiteral,
                 JavaScriptLexer.DecimalLiteral,
                 JavaScriptLexer.HexIntegerLiteral,
                 JavaScriptLexer.StringLiteral,
                 JavaScriptLexer.PlusPlus,
                 JavaScriptLexer.MinusMinus -> false;
            default -> true;
        };
    }

    @Override
    public void reset() {
        this.scopeStrictModes.clear();
        this.lastToken = null;
        this.useStrictDefault = false;
        this.useStrictCurrent = false;
        this.currentDepth = 0;
        this.templateDepthStack = new ArrayDeque<>();
        super.reset();
    }
}
