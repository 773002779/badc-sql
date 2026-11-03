package com.badc.sql.control.ticket;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.user.UserRoleService;

/**
 * 「变更前后结构对比」（FR-02 验收 1）。
 *
 * <p>本类只做三件事：判定能不能看、取出两次快照的引用、把比对交给执行器。比对逻辑在执行器侧——
 * 只有它持有 {@code schema.json}（快照文件写在执行器的卷上，控制面没有卷的挂载点）。
 *
 * <p><b>为什么"没有快照"是 409 而不是 404/空结果</b>：
 * <ul>
 *   <li>工单存在、调用方有权看——不满足 404 的语义；</li>
 *   <li>返回"没有差异"会被读成"这次变更没改任何结构"，而事实是"根本没有可比对的东西"。
 *       这两句在审核场景里结论相反：前者可以放心结束，后者必须去查为什么没抓到。</li>
 * </ul>
 *
 * <p>可见性复用 {@link TicketQueryService#requireVisible}：能看工单的人才能看它的结构变化，与工单详情、
 * 通知记录同一份判据。
 */
@Service
@Transactional(readOnly = true)
public class SchemaDiffService {

    /** 执行前快照（{@code V3} 的口径）。 */
    private static final int BEFORE_CHANGE_SEQ = 1;

    /** 执行后快照（本切片新增）。 */
    private static final int AFTER_CHANGE_SEQ = 2;

    private static final String REF_SQL = """
            select snapshot_ref
              from schema_snapshot
             where ticket_id = ? and change_seq = ?
             order by id desc
             limit 1
            """;

    private final TicketQueryService ticketQueryService;
    private final ExecutorClient executorClient;
    private final JdbcTemplate jdbc;

    public SchemaDiffService(TicketQueryService ticketQueryService, ExecutorClient executorClient,
            JdbcTemplate jdbc) {
        this.ticketQueryService = ticketQueryService;
        this.executorClient = executorClient;
        this.jdbc = jdbc;
    }

    /**
     * 计算某张工单执行前后的结构差异。
     *
     * @param userId   当前登录用户
     * @param ticketId 工单 ID
     * @return 逐表差异
     * @throws BizException 工单不存在 → 404；无权 → 403；还没执行 / 没抓到快照 → 409
     */
    public SchemaDiff diff(long userId, long ticketId) {
        ticketQueryService.requireVisible(userId, ticketId);
        String before = reference(ticketId, BEFORE_CHANGE_SEQ,
                "该工单还没有执行前快照：只有进入过执行的工单才有结构对比");
        String after = reference(ticketId, AFTER_CHANGE_SEQ,
                "该工单还没有执行后快照（可能还在执行中，或抓取失败），暂时无法给出结构差异");
        return executorClient.schemaDiff(before, after);
    }

    /** 取某次快照的引用；不存在时给出可操作的原因。 */
    private String reference(long ticketId, int changeSeq, String message) {
        List<String> refs = jdbc.queryForList(REF_SQL, String.class, ticketId, changeSeq);
        if (refs.isEmpty() || refs.get(0) == null || refs.get(0).isBlank()) {
            throw new BizException(ErrorCode.CONFLICT, message);
        }
        return refs.get(0);
    }
}
