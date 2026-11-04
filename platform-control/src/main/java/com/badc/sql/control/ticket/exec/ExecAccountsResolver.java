package com.badc.sql.control.ticket.exec;

import java.util.List;

import org.springframework.stereotype.Component;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;

/**
 * 一次执行要用到的数据库账号的解析器。
 *
 * <p><b>为什么单独抽出来</b>：工单执行与运维临时执行（{@code temp} 包）都要按语句类型惰性取账号。
 * 两处各写一份迟早会分叉，而分叉的后果是其中一个入口用错账号——例如拿 dml 账号执行 DDL，
 * 每条语句都在 MySQL 上以 1142 失败，变更单从"结构变更"退化成"每条都失败"。
 *
 * <p>账号缺失属于配置错误，本类抛 {@code BizException} 而不是返回空：调用方必须据此<b>不下发任何语句</b>，
 * 而不是拿着 {@code null} 去连库。
 */
@Component
public class ExecAccountsResolver {

    private final ConnAccountService connAccountService;

    public ExecAccountsResolver(ConnAccountService connAccountService) {
        this.connAccountService = connAccountService;
    }

    /**
     * 按语句类型解析需要的账号。
     *
     * <p>只解析真正需要的：一张纯 DDL 工单不该因为数据源没配 dml 账号而执行不了，反之亦然。
     * 账号缺失抛 {@code BizException}（配置错误），由调用方转成中止。
     */
    public Accounts resolve(long datasourceId, List<Classification> classifications) {
        boolean needsDdl = classifications.stream().anyMatch(c -> c.ddlAction() != DdlAction.NONE);
        boolean needsDmlExecute = classifications.stream()
                .anyMatch(c -> c.ddlAction() == DdlAction.NONE);
        boolean needsReadonly = classifications.stream()
                .anyMatch(c -> !c.writeStatement() && c.ddlAction() == DdlAction.NONE);

        ConnConfig dml = (needsDmlExecute || !needsDdl)
                ? connAccountService.dmlConnConfig(datasourceId)
                : null;
        ConnConfig ddl = needsDdl ? connAccountService.ddlConnConfig(datasourceId) : null;
        ConnConfig readonly = needsReadonly ? connAccountService.readonlyConnConfig(datasourceId) : null;
        return new Accounts(dml, ddl, readonly);
    }

    /** 一次执行会用到的账号。{@code ddl} 仅在含 DDL 语句时解析。 */
    public record Accounts(ConnConfig dml, ConnConfig ddl, ConnConfig readonly) {

        /**
         * 漂移检测与快照用的"执行账号"：有 DDL 就用 ddl，否则用 dml。
         *
         * <p><b>必须是 {@code public}</b>：{@code temp} 包里的临时执行服务在另一个包，包内可见调不到。
         */
        public ConnConfig execution() {
            return ddl != null ? ddl : dml;
        }
    }
}
