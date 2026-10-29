package com.badc.sql.executor.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.adapter.DataSourceAdapter;
import com.badc.sql.executor.exec.ExplainRunner;
import com.badc.sql.executor.exec.QueryRunner;
import com.badc.sql.executor.schema.SchemaSnapshotter;

/**
 * 执行器内部接口：只有控制面调用，不经公网暴露。
 *
 * <p>不做用户鉴权（身份在控制面已经验过），凭网络隔离；但<b>任何响应都不得含密码</b>。
 */
@RestController
@RequestMapping("/internal/v1")
public class ExecutorController {

    private final DataSourceAdapter adapter;
    private final QueryRunner queryRunner;
    private final SchemaSnapshotter snapshotter;
    private final ExplainRunner explainRunner;

    public ExecutorController(
            DataSourceAdapter adapter,
            QueryRunner queryRunner,
            SchemaSnapshotter snapshotter,
            ExplainRunner explainRunner) {
        this.adapter = adapter;
        this.queryRunner = queryRunner;
        this.snapshotter = snapshotter;
        this.explainRunner = explainRunner;
    }

    /**
     * 执行只读查询。行数上限由控制面按角色决定（研发固定 1001），执行器只负责不越界。
     */
    @PostMapping("/query")
    public RawResult query(@RequestBody QueryCall request) {
        if (request.conn() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return queryRunner.run(request.conn(), request.sql(), request.maxRows());
    }

    @PostMapping("/probe")
    public Capability probe(@RequestBody ProbeRequest request) {
        if (request.conn() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return adapter.probe(request.conn(), request.onlineDdlConn());
    }

    @PostMapping("/validate")
    public ValidationResult validate(@RequestBody ValidateRequest request) {
        return adapter.validate(request.sql(), request.dbVersion());
    }

    /**
     * 抓取结构快照并落盘。执行前由控制面调用；抓取失败会抛 {@code BizException}，控制面据此禁止执行。
     */
    @PostMapping("/snapshot")
    public SnapshotResult snapshot(@RequestBody SnapshotCall request) {
        if (request.conn() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return snapshotter.capture(request.conn(), request.tables(), request.relativeRef());
    }

    /**
     * 取表结构指纹。用于提交时与执行时的漂移比对；表不存在时该表不进 map，不抛异常。
     */
    @PostMapping("/fingerprint")
    public Map<String, String> fingerprint(@RequestBody FingerprintCall request) {
        if (request.conn() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return snapshotter.fingerprint(request.conn(), request.tables());
    }

    /**
     * EXPLAIN 行数估算。只发 {@code explain <sql>}，绝不执行原语句；无法估算时返回 {@code -1}。
     */
    @PostMapping("/explain")
    public ExplainEstimate explain(@RequestBody ExplainCall request) {
        if (request.conn() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return explainRunner.estimate(request.conn(), request.sql());
    }

    /** 探测入参。{@code onlineDdlConn} 为空表示该数据源未配置在线 DDL 专用账号。 */
    public record ProbeRequest(ConnConfig conn, ConnConfig onlineDdlConn) {
    }

    /** 校验入参。{@code dbVersion} 为空表示版本未知，此时不做版本判定。 */
    public record ValidateRequest(String sql, DbVersion dbVersion) {
    }

    /** 查询入参。{@code maxRows} 由控制面按角色决定。 */
    public record QueryCall(ConnConfig conn, String sql, int maxRows) {
    }

    /** 快照入参。{@code relativeRef} 形如 {@code {systemId}/{datasourceId}/{ticketId}/{changeSeq}}。 */
    public record SnapshotCall(ConnConfig conn, List<String> tables, String relativeRef) {
    }

    /** 指纹入参。 */
    public record FingerprintCall(ConnConfig conn, List<String> tables) {
    }

    /** EXPLAIN 入参。 */
    public record ExplainCall(ConnConfig conn, String sql) {
    }
}
