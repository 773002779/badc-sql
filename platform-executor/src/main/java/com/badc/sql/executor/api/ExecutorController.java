package com.badc.sql.executor.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.adapter.DataSourceAdapter;

/**
 * 执行器内部接口：只有控制面调用，不经公网暴露。
 *
 * <p>不做用户鉴权（身份在控制面已经验过），凭网络隔离；但<b>任何响应都不得含密码</b>。
 */
@RestController
@RequestMapping("/internal/v1")
public class ExecutorController {

    private final DataSourceAdapter adapter;

    public ExecutorController(DataSourceAdapter adapter) {
        this.adapter = adapter;
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

    /** 探测入参。{@code onlineDdlConn} 为空表示该数据源未配置在线 DDL 专用账号。 */
    public record ProbeRequest(ConnConfig conn, ConnConfig onlineDdlConn) {
    }

    /** 校验入参。{@code dbVersion} 为空表示版本未知，此时不做版本判定。 */
    public record ValidateRequest(String sql, DbVersion dbVersion) {
    }
}
