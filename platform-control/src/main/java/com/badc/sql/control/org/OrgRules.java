package com.badc.sql.control.org;

import java.util.Locale;
import java.util.Set;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 系统 / 模块 / 数据源管理的入参规则。
 *
 * <p>集中在这里而不是散落在各 service：四个写接口对名称、端口、账号类型的判定必须一致。
 * 所有失败都转成 {@link ErrorCode#INVALID_ARGUMENT}（HTTP 400）。
 */
final class OrgRules {

    static final String DB_TYPE_MYSQL = "MYSQL";
    static final String DEFAULT_CHARSET = "utf8mb4";
    static final String DEFAULT_TIME_ZONE = "+00:00";
    static final String READONLY_ACCOUNT = "readonly";

    private static final Set<String> ACCOUNT_TYPES = Set.of("readonly", "dml", "ddl", "onlineddl");

    private OrgRules() {
    }

    static String requireName(String value, String field) {
        return requireText(value, field, 128);
    }

    static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    static String optionalText(String value, String fallback, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        String chosen = trimmed.isEmpty() ? fallback : trimmed;
        if (chosen.length() > maxLength) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
        return chosen;
    }

    static int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "端口必须在 1-65535 之间");
        }
        return port;
    }

    static String normalizeDbType(String dbType) {
        String normalized = optionalText(dbType, DB_TYPE_MYSQL, "数据库类型", 32).toUpperCase(Locale.ROOT);
        if (!DB_TYPE_MYSQL.equals(normalized)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "本切片仅支持 MYSQL 数据源");
        }
        return normalized;
    }

    static String normalizeAccountType(String accountType) {
        String normalized = accountType == null ? "" : accountType.trim().toLowerCase(Locale.ROOT);
        if (!ACCOUNT_TYPES.contains(normalized)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "账号类型必须是 readonly|dml|ddl|onlineddl 之一");
        }
        return normalized;
    }

    static BizException notFound(String object, long id) {
        return new BizException(ErrorCode.NOT_FOUND, object + "不存在: " + id);
    }
}
