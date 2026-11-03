package com.badc.sql.control.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code schema_snapshot} 的写入。
 *
 * <p>三个序号，各有用途：
 * <ul>
 *   <li>{@code 0}：提交时的结构指纹（漂移检测的基线）；</li>
 *   <li>{@code 1}：执行前抓的完整快照（执行前结构，差异的"前"）；</li>
 *   <li>{@code 2}：执行后抓的完整快照（差异的"后"）。<b>抓取失败不写这一行</b>——差异接口随后会明确报
 *       "没有执行后快照"，而不是返回一份空差异（那会被读成"这次变更没改任何结构"）。</li>
 * </ul>
 *
 * <p>{@code table_fingerprints_json} 里表不存在记为 <b>空串</b>——"前置状态为空（表会被新建）"不是失败，
 * 与"抓取失败"（抛异常、不落库）必须能区分开。
 *
 * <p>注意 V3 里 {@code change_seq} 的列注释只写到 {@code 1}，是这条流水线扩展到执行后快照之前写的；
 * 改列注释需要一次迁移，而它不影响任何行为，所以留在原地（见交付说明的"上线前要知道的"）。
 */
@Repository
public class SchemaSnapshotMapper {

    private static final String INSERT = """
            insert into schema_snapshot
                (ticket_id, change_seq, datasource_id, snapshot_ref, table_fingerprints_json)
            values (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public SchemaSnapshotMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long ticketId, int changeSeq, long datasourceId,
            String snapshotRef, String tableFingerprintsJson) {
        jdbc.update(INSERT, ticketId, changeSeq, datasourceId, snapshotRef, tableFingerprintsJson);
    }
}
