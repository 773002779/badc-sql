package com.badc.sql.control.ticket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskHit;
import com.badc.sql.control.risk.RiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 整单风险快照：{@code ticket.risk_snapshot_json} 的规范化内容 + {@code risk_snapshot_hash} 的计算口径。
 *
 * <p>{@link RiskAssessment#snapshotHash()} 是<b>单条语句</b>的哈希，不能直接拿来当整单哈希：一张有 3 条
 * 语句的工单只存其中一条的哈希，另外两条被改了就看不出来。这里把整单的快照（等级 + 每条语句的等级与
 * 全部命中）序列化成固定形状，再叠加"全部语句按序拼接"的 SQL 哈希，得到整单哈希。
 *
 * <h2>哈希为什么同时覆盖快照与 SQL</h2>
 * <ul>
 *   <li><b>快照 JSON</b>：等级、每条语句的等级、规则码/纪律/说明，任何一处变化都会改变哈希；</li>
 *   <li><b>SQL 拼接哈希</b>：规则构成与等级恰好不变、但语句被改了一个字时，只有它能发现。
 *       这正是任务 9「风险未变才放行执行」与任务 11「语句未被篡改」不能互相替代的原因。</li>
 * </ul>
 * 拼接与 {@code StatementFingerprint} 同用 NUL 分隔并逐条 {@code stripTrailing}：行尾空白是复制粘贴噪音，
 * 不该被当成一次实质修改；NUL 不会出现在合法 SQL 里，不会把两条语句的边界拼错。
 *
 * <h2>为什么这里也包含每条语句的等级</h2>
 * <p>单条语句的等级可能因 EXPLAIN 估算变化而升降，而整单等级由最严的一条决定。若只把整单等级写进哈希，
 * "A 条从 L1 升到 L2、但整单仍被 B 条钉在 L3"这种变化会被哈希漏掉，执行前的确认就不会要求重新确认。
 *
 * <p><b>任务 9/11 必须复用本类</b>：执行前重算当前等级与命中后，用同一个方法算当前哈希，再与
 * {@code risk_acknowledgement.risk_snapshot_hash} 比对。自己另写一套口径会让"风险未变"永远对不上。
 */
public final class TicketRiskSnapshot {

    /** JSON 序列化器只用于本类的规范化输出，不接受外部配置（否则键序/日期格式变化会改哈希）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final char SEPARATOR = '\u0000';

    private TicketRiskSnapshot() {
    }

    /**
     * 整单快照内容。
     *
     * <p>字段顺序即 JSON 键序（record 的声明顺序），必须稳定：换序会改变哈希。
     *
     * @param level      整单等级（各条中最严）
     * @param statements 每条语句的等级与命中，按执行顺序
     */
    public record Snapshot(String level, List<StatementRisk> statements) {

        public Snapshot {
            Objects.requireNonNull(level, "level");
            statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        }
    }

    /**
     * 一条语句的风险构成。
     *
     * @param seq   工单内序号（从 1 开始）
     * @param level 该条语句的等级
     * @param rules 全部命中（含 WARN/INFO）；空列表表示无命中
     */
    public record StatementRisk(int seq, String level, List<Rule> rules) {

        public StatementRisk {
            Objects.requireNonNull(level, "level");
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        }
    }

    /**
     * 一条命中：只保留可稳定比对的三要素，不带 {@code RiskHit#level()}——它只在 PIN_LEVEL 时有值，
     * 且已由语句等级表达；把它写进 JSON 会让同一风险在 PIN 与 WARN 之间产生无意义的差异。
     */
    public record Rule(String ruleCode, String discipline, String message) {
    }

    /** 按语句顺序把各条评估拼成整单快照。 */
    public static Snapshot of(RiskLevel ticketLevel, List<RiskAssessment> assessments) {
        List<StatementRisk> statements = new ArrayList<>();
        int seq = 1;
        for (RiskAssessment assessment : assessments) {
            statements.add(new StatementRisk(seq++, assessment.level().name(), rulesOf(assessment.hits())));
        }
        return new Snapshot(ticketLevel.name(), statements);
    }

    /** 规范化 JSON 原文；字段顺序固定，可直接落库并参与排查比对。 */
    public static String json(Snapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // record 只含字符串与列表，序列化失败说明运行环境被裁剪过，不是用户输入问题。
            throw new IllegalStateException("风险快照序列化失败", e);
        }
    }

    /**
     * 整单风险快照哈希：{@code sha256(json + \0 + sha256(按序拼接的语句))}。
     *
     * @param ticketLevel 整单等级
     * @param assessments 按语句顺序的评估结果
     * @param statements  按语句顺序的原文
     * @return 64 位小写十六进制 SHA-256
     */
    public static String hash(RiskLevel ticketLevel, List<RiskAssessment> assessments, List<String> statements) {
        return hash(of(ticketLevel, assessments), statements);
    }

    /** 已算好快照时复用，避免重复序列化。 */
    public static String hash(Snapshot snapshot, List<String> statements) {
        return sha256(json(snapshot) + SEPARATOR + sha256(joinedStatements(statements)));
    }

    private static List<Rule> rulesOf(List<RiskHit> hits) {
        List<Rule> rules = new ArrayList<>(hits.size());
        for (RiskHit hit : hits) {
            rules.add(new Rule(hit.ruleCode(), hit.discipline().name(), hit.message()));
        }
        return rules;
    }

    private static String joinedStatements(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        boolean first = true;
        for (String sql : statements) {
            if (!first) {
                joined.append(SEPARATOR);
            }
            joined.append(Objects.requireNonNullElse(sql, "").stripTrailing());
            first = false;
        }
        return joined.toString();
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
