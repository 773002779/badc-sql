package com.badc.sql.common.dto;

import com.badc.sql.common.DbVersion;

/**
 * 数据源能力探测结果。
 *
 * <p>版本相关的判定全部集中在这里，且只依赖版本字符串——不连数据库也能测（本地没有 5.7 实例）。
 * 真正的探针只负责把 {@code @@version}、{@code @@log_bin} 读出来交给本类。
 *
 * <p>{@code version == null} 表示版本无法识别（如 MariaDB、非标准串），此时所有 8.0 专属能力一律
 * 按"不可用"处理——宁可不给入口，也不要让用户点了才报错。
 */
public record Capability(
        String versionString,
        DbVersion version,
        boolean supportsExplainAnalyze,
        boolean supportsInstantDdl,
        boolean binlogEnabled,
        boolean onlineDdlAvailable) {

    /** MySQL 8.0.18 起支持 {@code EXPLAIN ANALYZE}。 */
    private static final int EXPLAIN_ANALYZE_MINOR = 18;

    /** MySQL 8.0.12 起支持多数 {@code ALTER ... ALGORITHM=INSTANT}。 */
    private static final int INSTANT_DDL_MINOR = 12;

    /**
     * 由版本字符串推出能力。
     *
     * @param versionString 形如 {@code 8.0.36}、{@code 5.7.44-log}；无法识别时返回 version=null
     * @param binlogEnabled 服务器是否开启 binlog（来自 {@code @@log_bin}，不是从版本推）
     */
    public static Capability fromVersionString(String versionString, boolean binlogEnabled) {
        int[] parsed = parse(versionString);
        if (parsed == null) {
            return new Capability(versionString, null, false, false, binlogEnabled, false);
        }
        int major = parsed[0];
        int minor = parsed[1];

        DbVersion version;
        boolean explainAnalyze = false;
        boolean instantDdl = false;
        if (major == 5 && minor == 7) {
            version = DbVersion.MYSQL_5_7;
        } else if (major == 8 && minor == 0) {
            version = DbVersion.MYSQL_8_0;
            explainAnalyze = parsed[2] >= EXPLAIN_ANALYZE_MINOR;
            instantDdl = parsed[2] >= INSTANT_DDL_MINOR;
        } else if (major == 8) {
            // 8.1+ 同样具备这两项能力。
            version = DbVersion.MYSQL_8_0;
            explainAnalyze = true;
            instantDdl = true;
        } else {
            version = null;
        }
        return new Capability(versionString, version, explainAnalyze, instantDdl, binlogEnabled, false);
    }

    /**
     * 在线 DDL 是否可用：需要 binlog + 配置了 onlineddl 账号（由控制面决定是否传入）。
     * 版本不支持时恒为 false。
     */
    public Capability withOnlineDdlAvailable(boolean onlineDdlAccountUsable) {
        boolean available = version != null && binlogEnabled && onlineDdlAccountUsable;
        return new Capability(versionString, version, supportsExplainAnalyze, supportsInstantDdl, binlogEnabled, available);
    }

    /** 解析 {@code major.minor.patch}；无法解析返回 null。 */
    private static int[] parse(String versionString) {
        if (versionString == null) {
            return null;
        }
        String trimmed = versionString.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new int[] {
                    Integer.parseInt(digitsOf(parts[0])),
                    Integer.parseInt(digitsOf(parts[1])),
                    Integer.parseInt(digitsOf(parts[2]))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取前导数字：{@code 44-log} → {@code 44}；{@code mariadb10} → 抛 NumberFormatException。 */
    private static String digitsOf(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        return part.substring(0, end);
    }
}
