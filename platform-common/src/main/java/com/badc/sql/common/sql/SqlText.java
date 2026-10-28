package com.badc.sql.common.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 文本的轻量扫描工具：识别代码字符，把注释与字符串字面量排除在外。
 *
 * <p>方言判定（"这条 SQL 是否用了 CTE"）和 LIMIT 注入（"是否已有顶层 LIMIT"）都必须先排除
 * 注释与字面量，否则 {@code select 'WITH'} 会被当成 CTE、{@code where note='limit 10'} 会被当成
 * 已有 LIMIT。这类<b>假阳性</b>比漏判更伤：用户写对了却被拒或没被限流。
 */
public final class SqlText {

    /** 决定一条语句性质的顶层动词。 */
    private static final Set<String> TOP_LEVEL_VERBS = Set.of("select", "insert", "update", "delete", "replace");

    private SqlText() {
    }

    /**
     * 返回与入参等长的掩码：{@code true} 表示该位置属于 SQL 代码（不在注释或字面量内）。
     *
     * <p>等长是关键——调用方可以据此把"在掩码里找到的位置"直接用于原串切片。
     */
    public static boolean[] codeMask(String sql) {
        int n = sql == null ? 0 : sql.length();
        boolean[] isCode = new boolean[n];
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);

            // 行注释：# 或 --（后须空白）
            if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2))))) {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // 块注释
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }

            // 字符串字面量与反引号标识符
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
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

            isCode[i] = true;
            i++;
        }
        return isCode;
    }

    /**
     * 按顶层 {@code ;} 切分，返回用户原文的片段（只去掉两端空白，不做任何改写）。
     *
     * <p>{@code ;} 只有在 {@link #codeMask(String)} 判定为代码位置时才算分隔符：字符串、注释、反引号
     * 标识符里的分号都是正文内容。{@code insert into t values ('a;b')} 因此仍是一条语句。
     *
     * <p>空段（连续分号、纯空白、首尾分号）一律丢弃；{@code null} 与空白输入返回空列表。
     */
    public static List<String> splitStatements(String sql) {
        if (sql == null || sql.isEmpty()) {
            return List.of();
        }
        boolean[] isCode = codeMask(sql);
        List<String> statements = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (isCode[i] && sql.charAt(i) == ';') {
                addIfNotBlank(statements, sql.substring(start, i));
                start = i + 1;
            }
        }
        addIfNotBlank(statements, sql.substring(start));
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, String piece) {
        String stripped = piece.strip();
        if (!stripped.isEmpty()) {
            statements.add(stripped);
        }
    }

    /**
     * 括号深度 0 上<b>第一个</b>出现的 {@code select/insert/update/delete/replace}（小写）；没有返回
     * {@code null}。入参必须是已剥离注释与字面量的文本（调用方先调
     * {@link #stripLiteralsAndComments(String)}），否则 {@code 'with ... delete'} 这类字面量会被当成动词。
     *
     * <p>存在这个助手是因为"这是不是写语句"有两个调用方（查询接口的只读白名单、变更工单的语句分类），
     * 两处必须用同一套判定，否则同一条 SQL 会在两个入口得到相反结论。
     *
     * <p><b>取第一个而不是最后一个</b>：{@code with cte as (select 1) insert into t2 select id from cte}
     * 尾部的 {@code select} 属于 insert 的一部分，取最后一个会把它判成只读——那正是最危险的错误方向。
     * CTE 定义在括号里（深度 &gt; 0），CTE 名与 {@code as}/{@code recursive} 不是动词，都会被自然跳过。
     */
    public static String firstTopLevelVerb(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        int depth = 0;
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            int end = i;
            while (end < n && isWordChar(code.charAt(end))) {
                end++;
            }
            if (depth == 0) {
                String word = code.substring(i, end).toLowerCase(Locale.ROOT);
                if (TOP_LEVEL_VERBS.contains(word)) {
                    return word;
                }
            }
            i = end;
        }
        return null;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 把注释与字面量替换为空格，保留长度与换行，便于按关键字做整体匹配。 */
    public static String stripLiteralsAndComments(String sql) {
        if (sql == null) {
            return "";
        }
        boolean[] mask = codeMask(sql);
        char[] out = sql.toCharArray();
        for (int i = 0; i < out.length; i++) {
            if (!mask[i] && out[i] != '\n') {
                out[i] = ' ';
            }
        }
        return new String(out);
    }
}
