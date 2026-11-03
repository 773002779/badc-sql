package com.badc.sql.control.query;

import java.util.List;
import java.util.Map;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.dto.ValidationResult;

/**
 * 调用执行器的出向接口。
 *
 * <p>抽成接口是为了让控制面的测试能替换成桩——控制面要验证的是鉴权、可见性、只读白名单与
 * LIMIT 注入，不是 SQL 执行本身。
 *
 * <p>版本能力与方言校验也在执行器侧（只有执行器能连到目标库，也只有它持有方言实现），控制面只负责
 * 编排：探测一次、缓存、每次查询前校验。这三件事都经由本接口，控制面不复制方言规则。
 */
public interface ExecutorClient {

    RawResult query(ConnConfig conn, String sql, int maxRows);

    /**
     * 探测数据源能力（版本、{@code EXPLAIN ANALYZE} / INSTANT DDL 支持、binlog、在线 DDL）。
     *
     * @param conn             只读账号的连接配置
     * @param onlineDdlOrNull  在线 DDL 专用账号；数据源未配置时传 {@code null}
     * @throws com.badc.sql.common.error.BizException 探测失败（库不可达、权限不足、执行器不可用）
     */
    Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull);

    /**
     * 按目标库版本校验 SQL 是否用了该版本不支持的语法。
     *
     * @param conn    连接配置；当前执行器只按版本判定，保留该参数给后续按连接特性（如 {@code sql_mode}）判定
     * @param sql     已注入行数上限的最终 SQL——用户实际要执行的就是它，校验必须针对它
     * @param version 探测到的版本；调用方不应在版本未知时调用本方法
     * @throws com.badc.sql.common.error.BizException 执行器不可用（不是"SQL 不合法"，后者由返回值表达）
     */
    ValidationResult validate(ConnConfig conn, String sql, DbVersion version);

    /**
     * 取这些表的表结构指纹（表名 → sha256）。
     *
     * <p><b>空 map 只表示"这些表都不存在"</b>，也就是它们会被新建；连接失败、权限不足、执行器不可用
     * 一律由 {@link com.badc.sql.common.error.BizException} 表达，绝不降级成空 map。调用方据此区分
     * "新建表"与"库不可达"：前者按无前置结构处理，后者必须中止——两者处理方式相反，混淆会把一次
     * 探测失败当成"表不存在"，进而在错误的库上继续工单。
     *
     * @param conn   连接配置（执行期连接，含解密后的密码）
     * @param tables 要取指纹的表名；返回的 map 只含实际存在的表
     * @return 表名 → 结构指纹；不含不存在的表
     * @throws com.badc.sql.common.error.BizException 库不可达、权限不足或执行器不可用
     */
    Map<String, String> fingerprint(ConnConfig conn, List<String> tables);

    /**
     * 取一条语句的 EXPLAIN 行数估算，供风险分级按行数分档。
     *
     * <p>执行器只发 {@code explain <sql>}，<b>绝不执行原语句</b>；估算不出来时返回 {@code -1}
     * （见 {@link com.badc.sql.common.dto.ExplainEstimate}），调用方必须把 {@code -1} 当"最严"处理，
     * 不能当 0 行——两者在风险分级上差两级审批。
     *
     * <p>返回值是 {@code -1} 而不是抛异常：语句不合法、某行 {@code rows} 为 NULL 都属于"估不出来"，
     * 是正常结果而不是故障。库不可达、权限不足、执行器不可用才由
     * {@link com.badc.sql.common.error.BizException} 表达。
     *
     * @param conn 只读账号的连接配置（EXPLAIN 不写库，用只读账号即可）
     * @param sql  待估算的语句原文
     * @return 行数估算；{@code -1} 表示无法估算
     * @throws com.badc.sql.common.error.BizException 库不可达、权限不足或执行器不可用
     */
    ExplainEstimate explain(ConnConfig conn, String sql);

    /**
     * 抓取执行前的结构快照并落盘。
     *
     * <p><b>抛异常就是"抓取失败"，而失败必须禁止执行</b>（全局约束：结构快照抓取失败 → 禁止执行）。
     * 表不存在不是失败：它只是没有前置结构，不进入返回的 {@link SnapshotResult#tableFingerprints()}。
     * 调用方绝不能把异常吞成"没有表"——那等于在一个无法确认原始结构的库上执行结构变更。
     *
     * @param conn        执行账号的连接配置（已解密）
     * @param tables      本单涉及的表（已归一的裸表名）；为空表示不涉及已有表
     * @param relativeRef 落盘相对引用 {@code {systemId}/{datasourceId}/{ticketId}/{changeSeq}}，
     *                    由控制面给、执行器在快照根目录下解析
     * @return 落盘结果：引用、存在表的指纹、字节数
     * @throws com.badc.sql.common.error.BizException 库不可达、权限不足、执行器不可用或写盘失败
     */
    SnapshotResult snapshot(ConnConfig conn, List<String> tables, String relativeRef);

    /**
     * 比对两次结构快照（FR-02 验收 1）。
     *
     * <p>只读执行器侧的快照文件、<b>不连目标库</b>，所以不需要连接配置，只需要两个相对引用。
     *
     * <p><b>后一次快照不存在是 404，不是"没有差异"</b>：后者会被读成"这次变更什么都没改"，
     * 而事实是"根本没抓到可比的东西"。前一次缺失按空快照处理（建表工单：整张表都是新增）。
     *
     * @param beforeRef 执行前快照的相对引用 {@code {systemId}/{datasourceId}/{ticketId}/1}
     * @param afterRef  执行后快照的相对引用 {@code .../2}
     * @return 逐表差异，每项带人类可读的 before/after
     * @throws com.badc.sql.common.error.BizException 后一次快照不存在、快照读不出来或执行器不可用
     */
    SchemaDiff schemaDiff(String beforeRef, String afterRef);

    /**
     * 执行一条写语句。
     *
     * <p><b>失败是结果，不是异常</b>：语句本身的失败（语法错、约束冲突、超时）由
     * {@link ExecuteResult#success()} 为 {@code false} 表达，控制面据此逐条记录"哪条失败、为什么"。
     * 执行器不可用、入参非法才抛 {@link com.badc.sql.common.error.BizException}。
     *
     * <p><b>{@code success=false} 不等于"这条语句没有生效"</b>：{@code autoCommit=true} 下
     * {@code executeUpdate} 返回时已经提交，而连接关闭阶段的异常同样会落到失败分支。调用方不得据此
     * 断言"未执行"，只能按"执行没有全部成功、结果可能已生效"处理。
     *
     * @param conn 连接配置（执行账号，已解密）
     * @param sql  单条语句原文
     * @param type 账号类型，决定执行器侧的语句超时（DML 300s / DDL 3600s）
     * @return 本条语句的执行结果
     * @throws com.badc.sql.common.error.BizException 执行器不可用或入参非法
     */
    ExecuteResult execute(ConnConfig conn, String sql, ExecAccountType type);
}
