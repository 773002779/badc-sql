package com.badc.sql.executor.adapter.mysql;

import org.springframework.stereotype.Component;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.ValidationResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MySQL 版本方言校验。
 *
 * <p>只做一件事：判断这条 SQL 是否用了目标版本不支持的语法特性。真正的语法解析留给数据库
 * （执行时报错），这里只拦"版本不匹配"这一类，并且错误消息必须说清楚是<b>版本</b>问题，
 * 否则研发看到 1064 会去查自己的 SQL 拼写。
 *
 * <p><b>关键</b>：匹配前必须先剥掉注释与字符串字面量。代码里出现 {@code 'WITH'}、注释里出现
 * {@code -- with t as (...)} 都不代表用了 CTE。假阳性比漏判更伤——用户写对了却被拒。
 */
@Component
public class MySqlDialect {

    /** 5.7 不支持的 8.0 特性：正则 → 提示里的特性名。 */
    private static final Map<Pattern, String> NOT_IN_57 = featuresNotIn57();

    private static Map<Pattern, String> featuresNotIn57() {
        Map<Pattern, String> map = new LinkedHashMap<>();
        // WITH x AS ( —— 要求 x 后跟 AS(，避免把 "with" 当列名/别名的正常 SQL 误判。
        map.put(Pattern.compile("(^|\\(|\\s)with\\s+[a-z0-9_$]+\\s*(?:\\([^)]*\\))?\\s+as\\s*\\("), "WITH 子句（CTE）");
        map.put(Pattern.compile("\\bover\\s*\\("), "窗口函数（OVER）");
        map.put(Pattern.compile("->>"), "JSON ->> 运算符");
        map.put(Pattern.compile("\\bjson_table\\s*\\("), "JSON_TABLE 函数");
        map.put(Pattern.compile("\\brecursive\\b"), "递归 CTE");
        return Map.copyOf(map);
    }

    /**
     * 校验 {@code sql} 在 {@code version} 上是否可用。
     *
     * @return 不通过时 {@code ok=false}，消息里含版本号与特性名
     */
    public ValidationResult validate(String sql, DbVersion version) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.reject("INVALID_ARGUMENT", "SQL 不能为空");
        }
        if (version == null) {
            // 版本未知：不做版本判定，交给数据库本身报错。
            return ValidationResult.success();
        }
        if (version != DbVersion.MYSQL_5_7) {
            return ValidationResult.success();
        }

        String code = stripLiteralsAndComments(sql).toLowerCase();
        for (Map.Entry<Pattern, String> entry : NOT_IN_57.entrySet()) {
            if (entry.getKey().matcher(code).find()) {
                return ValidationResult.reject(
                        "DB_VERSION_UNSUPPORTED",
                        "该库为 MySQL 5.7，不支持 " + entry.getValue() + "；请改写 SQL 或改用 8.0 实例");
            }
        }
        return ValidationResult.success();
    }

    /**
     * 把字符串字面量、反引号标识符、以及 {@code --} / {@code #} / {@code /* *}{@code /} 注释替换成空格，
     * 只留下真正的代码文本。保留换行以尽量维持可读性（也避免把两行拼成一个词）。
     */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);

            // 行注释：-- 后必须跟空白（MySQL 规则），或 # 开头
            if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2))))) {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }

            // 块注释
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }

            // 字符串字面量与反引号标识符：整体跳过，内容不参与关键字匹配
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
                        // '' 或 "" 是转义的双写，不是结束
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }
}
