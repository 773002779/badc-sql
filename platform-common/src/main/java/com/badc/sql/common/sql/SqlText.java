package com.badc.sql.common.sql;

/**
 * SQL 文本的轻量扫描工具：识别代码字符，把注释与字符串字面量排除在外。
 *
 * <p>方言判定（"这条 SQL 是否用了 CTE"）和 LIMIT 注入（"是否已有顶层 LIMIT"）都必须先排除
 * 注释与字面量，否则 {@code select 'WITH'} 会被当成 CTE、{@code where note='limit 10'} 会被当成
 * 已有 LIMIT。这类<b>假阳性</b>比漏判更伤：用户写对了却被拒或没被限流。
 */
public final class SqlText {

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
