package com.badc.sql.control.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code schema_snapshot} 的写入。
 *
 * <p>提交时写 {@code change_seq = 0}（结构指纹），执行前任务 11 再写 {@code change_seq = 1}（执行前快照）。
 * {@code table_fingerprints_json} 里表不存在记为 <b>空串</b>——"前置状态为空（表会被新建）"不是失败，
 * 与"抓取失败"（抛异常、不落库）必须能区分开。
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
