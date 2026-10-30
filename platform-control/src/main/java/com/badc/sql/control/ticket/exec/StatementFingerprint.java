package com.badc.sql.control.ticket.exec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 工单语句指纹：{@code sha256} over 数据源 ID + 语句序号 + 每条 {@code stripTrailing} 后的原文。
 *
 * <p><b>三个分量缺一不可</b>：
 * <ul>
 *   <li><b>数据源 ID</b>：审核后被"换库执行"是最危险的篡改——同一组语句换到另一个库上，风险等级、
 *       审批链、结构快照全都不一样。指纹变了，任务 11 的校验就能在发第一条语句之前拦下。</li>
 *   <li><b>序号</b>：同一组语句换个顺序就是另一张工单（先删后插与先插后删的副作用完全不同）。</li>
 *   <li><b>原文</b>：改一个字都要能看出来；用 {@link String#stripTrailing()} 而不是 {@code trim()}，
 *       行尾换行/空格是复制粘贴的常见噪音，不该让同一张工单"看起来被改过"。</li>
 * </ul>
 *
 * <p>各分量之间用 {@code \u0000} 分隔并带序号：不用换行或 {@code |} 拼字符串，否则
 * {@code ["a\n", "b"]} 与 {@code ["a", "\nb"]} 这类边界会在 {@code stripTrailing} 后撞成同一个哈希。
 * NUL 不会出现在合法 SQL 文本里，分隔是唯一的。
 *
 * <p>本类是纯函数（任务 11 会拿它重算当前语句文本的指纹并与 {@code ticket.statement_fingerprint}
 * 比对），不连库、无状态。
 */
public final class StatementFingerprint {

    /** 分量分隔符：合法 SQL 文本里不会出现的 NUL。 */
    private static final char SEPARATOR = '\u0000';

    private StatementFingerprint() {
    }

    /**
     * 计算工单的语句指纹。
     *
     * @param datasourceId 目标数据源 ID
     * @param statements   按执行顺序排列的语句原文；{@code null} 按空列表处理
     * @return 64 位小写十六进制 SHA-256
     */
    public static String of(long datasourceId, List<String> statements) {
        List<String> texts = statements == null ? List.of() : statements;
        StringBuilder material = new StringBuilder();
        material.append(datasourceId);
        int seq = 1;
        for (String sql : texts) {
            material.append(SEPARATOR)
                    .append(seq++)
                    .append(SEPARATOR)
                    .append(Objects.requireNonNullElse(sql, "").stripTrailing());
        }
        return sha256(material.toString());
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境被裁剪过。
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
