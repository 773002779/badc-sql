package com.badc.sql.control.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 审计事件链。
 *
 * <p>每条事件的哈希覆盖前一条的 {@code seq} 与 {@code hash}，因此改动任意一条都会让其后所有记录失配。
 *
 * <p><b>并发</b>：{@code seq} 由应用赋值而不是自增，多线程同时写会分叉出两条链。写入因此串行化
 * （单实例内 {@code synchronized}；跨实例靠 {@code seq} 唯一索引冲突后重试兜底）。
 *
 * <p><b>链头必须是加锁读</b>（{@link #NEXT_HEAD_SQL} 的 {@code for update}，不要改回普通 {@code select}）：
 * 审计写入自任务 7 起与业务落库<b>同事务</b>，而普通 {@code select} 在 MySQL 默认的 REPEATABLE READ 下读的
 * 是事务第一次读时的快照。两个并发事务会各自读到同一个 {@code seq}，一个成功、另一个撞唯一键；它的重试
 * <b>仍然</b>读同一个旧快照，三次都撞同一个 {@code seq} → 500。加锁读不受快照限制，总是读最新已提交版本，
 * 并且把并发追加串行化——哈希链本来就不能并行追加。唯一键冲突重试保留为第二道保险（跨实例、异常路径）。
 *
 * <p><b>大小写</b>：V1 里 {@code hash} 是 {@code utf8mb4_0900_ai_ci}，SQL 比较不区分大小写。
 * 校验一律在应用层用 {@link String#equals} 比较原始字符串，避免 {@code A} 与 {@code a} 被当成同一个哈希。
 */
@Service
public class AuditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 链头读取：<b>必须是加锁读</b>。
     *
     * <p>普通 {@code select} 在 REPEATABLE READ 下会被事务快照钉住，并发事务的重试读不到别人刚提交的
     * {@code seq}，最终三次唯一键冲突后失败（见类注释）。{@code for update} 总是读最新已提交版本，同时让
     * 并发追加在链头上排队。空表时它锁住 supremum gap，同样能挡住并发插入。
     */
    private static final String NEXT_HEAD_SQL =
            "select seq, hash from audit_event order by seq desc limit 1 for update";

    private static final String INSERT_SQL = """
            insert into audit_event
                (seq, event_type, operator_id, exec_identity, datasource_id, action, object_id,
                 payload_json, prev_hash, hash, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String ALL_SQL = """
            select seq, event_type, operator_id, exec_identity, datasource_id, action, object_id,
                   payload_json, prev_hash, hash
              from audit_event
             order by seq
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Object chainLock = new Object();

    public AuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 追加一条审计事件，返回其 {@code seq}。 */
    public long record(AuditCommand command) {
        String canonical = canonicalJson(command.payloadJson());
        synchronized (chainLock) {
            for (int attempt = 0; attempt < 3; attempt++) {
                long prevSeq = 0L;
                String prevHash = "";
                List<Map<String, Object>> head = jdbc.queryForList(NEXT_HEAD_SQL);
                if (!head.isEmpty()) {
                    prevSeq = ((Number) head.get(0).get("seq")).longValue();
                    prevHash = (String) head.get(0).get("hash");
                }
                long seq = prevSeq + 1;
                String hash = hash(prevSeq, prevHash, command.eventType(), command.operatorId(),
                        command.execIdentity(), command.datasourceId(), command.action(), command.objectId(), canonical);
                try {
                    jdbc.update(INSERT_SQL,
                            seq,
                            command.eventType(),
                            command.operatorId(),
                            command.execIdentity(),
                            command.datasourceId(),
                            command.action(),
                            command.objectId(),
                            canonical,
                            prevHash,
                            hash,
                            Timestamp.from(clock.instant()));
                    return seq;
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // 第二道保险：跨实例并发时仍可能撞同一个 seq。加锁读保证下一次重试能读到最新链头
                    // （普通 select 会被事务快照钉住，重试永远读到旧 seq）。
                }
            }
        }
        throw new IllegalStateException("审计链写入失败：seq 冲突重试耗尽");
    }

    /** 校验整条链；返回第一处断裂的 {@code seq}（无断裂时 {@code brokenAtSeq} 为 null）。 */
    public VerifyResult verify() {
        List<Map<String, Object>> rows = jdbc.queryForList(ALL_SQL);
        long expectedSeq = 1L;
        String prevHash = "";
        for (Map<String, Object> row : rows) {
            long seq = ((Number) row.get("seq")).longValue();
            String storedPrev = (String) row.get("prev_hash");
            String storedHash = (String) row.get("hash");
            if (seq != expectedSeq) {
                return new VerifyResult(false, seq);
            }
            // 大小写敏感：必须用 equals，不能用 SQL 的 = 比较。
            if (!prevHash.equals(storedPrev)) {
                return new VerifyResult(false, seq);
            }
            String recomputed = hash(
                    seq - 1,
                    storedPrev,
                    (String) row.get("event_type"),
                    row.get("operator_id") == null ? null : ((Number) row.get("operator_id")).longValue(),
                    (String) row.get("exec_identity"),
                    row.get("datasource_id") == null ? null : ((Number) row.get("datasource_id")).longValue(),
                    (String) row.get("action"),
                    (String) row.get("object_id"),
                    (String) row.get("payload_json"));
            if (!recomputed.equals(storedHash)) {
                return new VerifyResult(false, seq);
            }
            prevHash = storedHash;
            expectedSeq++;
        }
        return new VerifyResult(true, null);
    }

    /** 校验结果。 */
    public record VerifyResult(boolean valid, Long brokenAtSeq) {
    }

    private static String hash(long prevSeq, String prevHash, String eventType, Long operatorId,
                               String execIdentity, Long datasourceId, String action, String objectId,
                               String canonicalPayload) {
        String material = prevSeq + "|" + prevHash + "|" + eventType + "|" + operatorId
                + "|" + execIdentity + "|" + datasourceId + "|" + action + "|" + objectId
                + "|" + canonicalPayload;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /**
     * 把任意 JSON 文本规范化：对象键按字典序递归排序，数组保持顺序。
     *
     * <p>没有这一步，{@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 会算出不同哈希，而它们在语义上
     * 是同一条事件——链会自己和自己对不上。非法 JSON 原样返回（宁可留下可追查的原文）。
     */
    static String canonicalJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return MAPPER.writeValueAsString(sort(node));
        } catch (Exception e) {
            return json;
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), sort(entry.getValue()));
            }
            ObjectNode rebuilt = MAPPER.createObjectNode();
            sorted.forEach(rebuilt::set);
            return rebuilt;
        }
        if (node instanceof ArrayNode array) {
            List<JsonNode> items = new ArrayList<>();
            array.forEach(item -> items.add(sort(item)));
            ArrayNode rebuilt = MAPPER.createArrayNode();
            items.forEach(rebuilt::add);
            return rebuilt;
        }
        return node;
    }

    /** 只读链，供导出/排查使用（保持只追加语义，不提供更新与删除）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tail(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList(ALL_SQL));
        Collections.reverse(rows);
        return rows.subList(0, Math.min(limit, rows.size()));
    }
}
