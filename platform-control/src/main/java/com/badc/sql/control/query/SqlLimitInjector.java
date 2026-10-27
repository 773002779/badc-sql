package com.badc.sql.control.query;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.badc.sql.common.sql.SqlText;

/**
 * 给研发的 {@code select} 加上行数上限。
 *
 * <p>规则见 {@code SqlLimitInjectorTest}：没有顶层 LIMIT 就追加；已有且不超上限就保留；超过上限则
 * 包一层外层 LIMIT（改内层会改变聚合语义）。子查询里的 LIMIT 不算顶层。
 *
 * <p>只处理会返回行的语句（{@code select} / {@code with} / {@code show} / {@code desc} /
 * {@code explain}），其它语句原样返回。
 */
@Component
public class SqlLimitInjector {

    /**
     * 只对真正返回数据的语句注入上限。
     *
     * <p>{@code show} / {@code desc} / {@code explain} 返回的是元数据，行数本来就少；给它们加上限
     * 只会让 SQL 变奇怪，也让用户困惑。
     */
    private static final Set<String> ROW_RETURNING = Set.of("select", "with");

    private static final String WRAP_ALIAS = "__badc_cap";

    private final int cap;

    public SqlLimitInjector() {
        this(1001);
    }

    public SqlLimitInjector(int cap) {
        this.cap = cap;
    }

    public int cap() {
        return cap;
    }

    /** 返回加上上限后的 SQL；不需要限流时原样返回。 */
    public String apply(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        // B5：先去掉行尾注释与空白，否则追加的 LIMIT 会被 `-- ...` / `# ...` 吞掉。
        sql = trimTrailingIgnorable(sql);
        if (!isRowReturning(sql)) {
            return sql;
        }

        int topLimit = findTopLevelLimit(sql);
        boolean trailingSemicolon = endsWithSemicolon(sql);
        String body = stripTrailingSemicolon(sql);

        if (topLimit < 0) {
            return body + " limit " + cap + (trailingSemicolon ? ";" : "");
        }

        long existing = parseRowCount(sql, topLimit);
        if (existing <= cap) {
            return sql;
        }
        return "select * from (" + body + ") " + WRAP_ALIAS + " limit " + cap + (trailingSemicolon ? ";" : "");
    }

    private boolean isRowReturning(String sql) {
        String stripped = SqlText.stripLiteralsAndComments(sql);
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                continue;
            }
            int end = i;
            while (end < stripped.length() && Character.isLetter(stripped.charAt(end))) {
                end++;
            }
            return ROW_RETURNING.contains(stripped.substring(i, end).toLowerCase(Locale.ROOT));
        }
        return false;
    }

    /**
     * 找最外层（括号深度 0）的最后一个 {@code limit} 关键字位置；没有返回 -1。
     *
     * <p>子查询里的 {@code limit} 深度 &gt; 0，不算顶层。
     */
    private int findTopLevelLimit(String sql) {
        boolean[] isCode = SqlText.codeMask(sql);
        String lower = sql.toLowerCase(Locale.ROOT);
        int depth = 0;
        int found = -1;
        for (int i = 0; i < sql.length(); i++) {
            if (!isCode[i]) {
                continue;
            }
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth == 0 && c == 'l' && isLimitKeywordAt(lower, i, isCode)) {
                found = i;
                i += 4;
            }
        }
        return found;
    }

    private static boolean isLimitKeywordAt(String lower, int index, boolean[] isCode) {
        if (index + 5 > lower.length()) {
            return false;
        }
        if (!lower.startsWith("limit", index)) {
            return false;
        }
        for (int k = index; k < index + 5; k++) {
            if (!isCode[k]) {
                return false;
            }
        }
        boolean beforeOk = index == 0 || !Character.isLetterOrDigit(lower.charAt(index - 1));
        int after = index + 5;
        boolean afterOk = after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
        return beforeOk && afterOk;
    }

    /** 解析 {@code limit N} / {@code limit M,N} / {@code limit N offset M} 的行数部分。 */
    private static long parseRowCount(String sql, int limitIndex) {
        String stripped = SqlText.stripLiteralsAndComments(sql);
        String tail = stripped.substring(limitIndex + 5).trim();
        String digits = leadingNumber(tail);
        if (digits.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long first = safeParse(digits);
        String rest = tail.substring(digits.length());
        String restTrimmed = rest.trim();
        if (restTrimmed.startsWith(",")) {
            // limit offset, rows
            String rows = leadingNumber(restTrimmed.substring(1).trim());
            return rows.isEmpty() ? Long.MAX_VALUE : safeParse(rows);
        }
        // limit rows [offset ...]
        return first;
    }

    private static String leadingNumber(String text) {
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    private static long safeParse(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean endsWithSemicolon(String sql) {
        String trimmed = sql.stripTrailing();
        return !trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == ';';
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql.stripTrailing();
        if (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == ';') {
            return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }
    /**
     * 去掉尾部的注释与空白，只保留代码部分。
     *
     * <p>MySQL 的行注释一直延续到行尾，若直接追加 {@code limit}，它会成为注释的一部分而失效——
     * 语句照样全表执行，与"研发单次只能取 1001 行"的承诺相悖。
     */
    static String trimTrailingIgnorable(String sql) {
        int n = sql.length();
        // content[i]=true 表示该字符属于 SQL 正文（含字符串字面量内部），注释与空白不算。
        boolean[] content = new boolean[n];
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2))))) {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                content[i] = true;
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    content[i] = true;
                    if (d == '\\' && quote != '`' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            content[i] = true;
            i++;
        }
        int end = -1;
        for (int k = 0; k < n; k++) {
            if (content[k] && !Character.isWhitespace(sql.charAt(k))) {
                end = k;
            }
        }
        return end < 0 ? "" : sql.substring(0, end + 1);
    }
}