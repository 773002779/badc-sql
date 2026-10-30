package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.ticket.TicketQueryMapper.EventRow;
import com.badc.sql.control.ticket.TicketQueryMapper.ReviewRow;
import com.badc.sql.control.ticket.TicketQueryMapper.Scope;
import com.badc.sql.control.ticket.TicketQueryMapper.SnapshotRow;
import com.badc.sql.control.ticket.TicketQueryMapper.StatementRow;
import com.badc.sql.control.ticket.TicketQueryMapper.SummaryRow;
import com.badc.sql.control.user.UserRoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工单读路径：列表与详情。
 *
 * <h2>可见性（本服务唯一的业务判断）</h2>
 * <ul>
 *   <li>{@code DEVELOPER}：只看自己提交的工单；</li>
 *   <li>{@code PRIMARY_OPS} / {@code BACKUP_OPS} / {@code OPS_LEAD}：看工单所属 {@code system_id} 在自己
 *       {@code sys_system_operator} 范围内的工单；</li>
 *   <li>{@code ADMIN}：全部（管理员负责全局治理，工单里不含凭证）；</li>
 *   <li>其它角色（含用户不存在）：什么都看不到。</li>
 * </ul>
 *
 * <p>列表过滤在 SQL 里完成（{@link TicketQueryMapper#count} 与 {@link TicketQueryMapper#page} 共用同一个
 * 谓词），{@code total} 因此永远与分页口径一致。详情先按 id 查工单区分 404，再用<b>同一套</b>谓词判定是否
 * 可见：知道 id 不能绕过列表过滤；无权是 403，绝不伪装成 404。
 *
 * <h2>risk_snapshot_json</h2>
 * <p>存的是规范化原文（不用 MySQL JSON 类型以免键序被重排），响应里必须给前端解析后的对象——前端要用它
 * 渲染命中规则与逐条等级。一条坏 JSON 只让这一张工单的 {@code riskSnapshot} 变成 {@code null} 并记
 * {@code log.warn}，<b>不能</b>把详情接口打成 500，也不能把解析异常文本回给前端。
 */
@Service
public class TicketQueryService {

    private static final Logger log = LoggerFactory.getLogger(TicketQueryService.class);

    /** 分页上限：一次最多 100 条，防止前端传个天文数字把内存打满。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final TicketMapper ticketMapper;
    private final TicketQueryMapper queryMapper;
    private final UserRoleService userRoleService;
    private final ObjectMapper objectMapper;

    public TicketQueryService(
            TicketMapper ticketMapper,
            TicketQueryMapper queryMapper,
            UserRoleService userRoleService,
            ObjectMapper objectMapper) {
        this.ticketMapper = ticketMapper;
        this.queryMapper = queryMapper;
        this.userRoleService = userRoleService;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询当前用户可见的工单，按创建时间倒序。
     *
     * @param userId 当前登录用户
     * @param page   页码，从 1 开始；小于 1 按 1 处理
     * @param size   页大小；小于 1 按 1、大于 100 按 100 处理
     */
    public TicketPage list(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Scope scope = scopeOf(userRoleService.roleOf(userId));

        long total = queryMapper.count(userId, scope);
        long offset = (long) (safePage - 1) * safeSize;
        List<Summary> items = queryMapper.page(userId, scope, safeSize, offset).stream()
                .map(TicketQueryService::summaryOf)
                .toList();
        return new TicketPage(total, safePage, safeSize, items);
    }

    /**
     * 工单详情。
     *
     * @throws BizException 工单不存在 → 404；存在但当前用户无权 → 403
     */
    public TicketDetail detail(long userId, long ticketId) {
        TicketEntity ticket = ticketMapper.findById(ticketId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工单不存在：" + ticketId));

        Scope scope = scopeOf(userRoleService.roleOf(userId));
        // 详情必须重新判定：列表过滤只是让工单不出现，不能阻止知道 id 的人直接访问。
        if (!queryMapper.isVisible(ticketId, userId, scope)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该工单：" + ticketId);
        }
        return detailOf(ticket);
    }

    private TicketDetail detailOf(TicketEntity ticket) {
        long ticketId = ticket.id();
        return new TicketDetail(
                ticketId,
                ticket.status(),
                ticket.riskLevel(),
                ticket.ticketType(),
                ticket.systemId(),
                ticket.moduleId(),
                ticket.datasourceId(),
                ticket.title(),
                ticket.changeReason(),
                ticket.rollbackPlan(),
                ticket.rollbackSql(),
                ticket.statementFingerprint(),
                ticket.riskSnapshotHash(),
                parseJson(ticket.riskSnapshotJson(), ticketId, "risk_snapshot_json"),
                ticket.submitterId(),
                ticket.originTicketId(),
                ticket.rollbackReason(),
                ticket.expectedExecuteAt(),
                ticket.createdAt(),
                ticket.updatedAt(),
                ticket.approvedAt(),
                ticket.executedAt(),
                ticket.finishedAt(),
                queryMapper.statements(ticketId).stream()
                        .map(TicketQueryService::statementOf).toList(),
                queryMapper.reviews(ticketId).stream()
                        .map(TicketQueryService::reviewOf).toList(),
                queryMapper.snapshots(ticketId).stream()
                        .map(row -> snapshotOf(row, ticketId)).toList(),
                queryMapper.events(ticketId).stream()
                        .map(TicketQueryService::eventOf).toList());
    }

    /**
     * 解析库里的 JSON 原文；失败返回 {@code null} 并记日志。
     *
     * <p>只记录字段名、工单 ID 与 Jackson 的原始消息，<b>不记录原文全文</b>（可能是坏在中间的大段文本）；
     * 响应里更不会出现异常文本。
     */
    private JsonNode parseJson(String raw, long ticketId, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            log.warn("工单 {} 的 {} 不是合法 JSON，响应中置为 null：{}", ticketId, field, e.getOriginalMessage());
            return null;
        }
    }

    private static Summary summaryOf(SummaryRow row) {
        return new Summary(
                row.id(),
                row.status(),
                row.riskLevel(),
                row.systemId(),
                row.moduleId(),
                row.datasourceId(),
                row.title(),
                row.submitterId(),
                row.ticketType(),
                row.createdAt(),
                row.expectedExecuteAt());
    }

    private static StatementView statementOf(StatementRow row) {
        return new StatementView(
                row.seq(),
                row.statementType(),
                row.riskLevel(),
                row.status(),
                row.affectedRows(),
                row.errorMessage(),
                row.elapsedMs(),
                row.executedAt(),
                row.sql());
    }

    private static ReviewView reviewOf(ReviewRow row) {
        return new ReviewView(
                row.reviewLevel(),
                row.reviewerId(),
                row.decision(),
                row.comment(),
                row.createdAt());
    }

    private SnapshotView snapshotOf(SnapshotRow row, long ticketId) {
        return new SnapshotView(
                row.changeSeq(),
                row.snapshotRef(),
                parseJson(row.tableFingerprintsJson(), ticketId, "table_fingerprints_json"),
                row.capturedAt());
    }

    /** 事件明细原样返回：它是给业务看的追加流水，不是参与哈希的规范化 JSON。 */
    private static EventView eventOf(EventRow row) {
        return new EventView(
                row.eventType(),
                row.actorId(),
                row.detailJson(),
                row.createdAt());
    }

    /**
     * 角色 → 可见性范围。
     *
     * <p>只在这里做一次映射，列表与详情共用；漏掉任何角色都落到 {@link Scope#NONE}（fail-closed）。
     */
    private static Scope scopeOf(String role) {
        if (role == null) {
            return Scope.NONE;
        }
        return switch (role) {
            case "DEVELOPER" -> Scope.SELF;
            case "PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD" -> Scope.OPERATED_SYSTEMS;
            case "ADMIN" -> Scope.ALL;
            default -> Scope.NONE;
        };
    }

    /**
     * 一页工单。
     *
     * @param total 可见范围内的总数（不是全表数）
     * @param page  实际生效的页码
     * @param size  实际生效的页大小
     * @param items 当前页
     */
    public record TicketPage(long total, int page, int size, List<Summary> items) {

        public TicketPage {
            items = List.copyOf(items);
        }
    }

    /** 列表项。 */
    public record Summary(
            long id,
            String status,
            String riskLevel,
            long systemId,
            long moduleId,
            long datasourceId,
            String title,
            long submitterId,
            String ticketType,
            Instant createdAt,
            Instant expectedExecuteAt) {
    }

    /**
     * 工单详情。
     *
     * @param riskSnapshot {@code risk_snapshot_json} 解析后的对象；坏 JSON 时为 {@code null}
     */
    public record TicketDetail(
            long id,
            String status,
            String riskLevel,
            String ticketType,
            long systemId,
            long moduleId,
            long datasourceId,
            String title,
            String changeReason,
            String rollbackPlan,
            String rollbackSql,
            String statementFingerprint,
            String riskSnapshotHash,
            JsonNode riskSnapshot,
            long submitterId,
            Long originTicketId,
            String rollbackReason,
            Instant expectedExecuteAt,
            Instant createdAt,
            Instant updatedAt,
            Instant approvedAt,
            Instant executedAt,
            Instant finishedAt,
            List<StatementView> statements,
            List<ReviewView> reviews,
            List<SnapshotView> snapshots,
            List<EventView> events) {

        public TicketDetail {
            statements = List.copyOf(statements);
            reviews = List.copyOf(reviews);
            snapshots = List.copyOf(snapshots);
            events = List.copyOf(events);
        }
    }

    /** 详情里的一条语句。 */
    public record StatementView(
            int seq,
            String statementType,
            String riskLevel,
            String status,
            Long affectedRows,
            String errorMessage,
            Long elapsedMs,
            Instant executedAt,
            String sql) {
    }

    /** 详情里的一条审核记录。 */
    public record ReviewView(
            int reviewLevel,
            long reviewerId,
            String decision,
            String comment,
            Instant createdAt) {
    }

    /** 详情里的一条结构快照；{@code tableFingerprints} 是解析后的对象。 */
    public record SnapshotView(
            int changeSeq,
            String snapshotRef,
            JsonNode tableFingerprints,
            Instant capturedAt) {
    }

    /** 详情里的一条事件；{@code detail} 是原样的事件明细 JSON 文本。 */
    public record EventView(
            String eventType,
            Long actorId,
            String detail,
            Instant createdAt) {
    }
}
