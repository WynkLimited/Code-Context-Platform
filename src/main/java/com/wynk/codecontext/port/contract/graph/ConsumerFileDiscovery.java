package com.wynk.codecontext.port.contract.graph;

/** Exact language-neutral identifier boundary helper used by the reference prompt-safety utility. */
public final class ConsumerFileDiscovery {
    private ConsumerFileDiscovery() {}
    public static boolean containsIdentifier(String content, String symbol) {
        if (content == null || symbol == null || symbol.isEmpty()) return false;
        int from = 0;
        while (true) {
            int at = content.indexOf(symbol, from);
            if (at < 0) return false;
            boolean leftClear = at == 0 || !isIdentifierChar(content.charAt(at - 1));
            int end = at + symbol.length();
            boolean rightClear = end >= content.length() || !isIdentifierChar(content.charAt(end));
            if (leftClear && rightClear) return true;
            from = at + 1;
        }
    }
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
