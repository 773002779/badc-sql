package com.badc.sql.control.org;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.grant.VisibilityService;
import com.badc.sql.control.security.CurrentUser;

/**
 * 数据源维护：元数据、连接参数、账号集。
 *
 * <p>列表只返回 {@link DataSourceView}（id/name/dbName/systemId/type），连接信息与凭证绝不出现在
 * 任何响应里；列表按登录用户的可见范围收敛（§4.3-8：不是隐藏，是不查）。写接口仅运维 / 管理员，
 * 且运维只能改<b>自己负责的系统</b>下的数据源。
 */
@RestController
@RequestMapping("/api/v1/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final ConnAccountService connAccountService;
    private final OperatorGuard operatorGuard;
    private final VisibilityService visibilityService;

    public DataSourceController(
            DataSourceService dataSourceService,
            ConnAccountService connAccountService,
            OperatorGuard operatorGuard,
            VisibilityService visibilityService) {
        this.dataSourceService = dataSourceService;
        this.connAccountService = connAccountService;
        this.operatorGuard = operatorGuard;
        this.visibilityService = visibilityService;
    }

    /**
     * 列表按可见性过滤：研发只看得到已授权模块下的库（§4.3-8「不是隐藏，是不查」）。
     *
     * <p>不过滤就等于把全部生产库的库名与所属系统暴露给任何登录用户，还会让前端列出点了就 403 的库。
     *
     * <p>可见集合为 null 时按空集处理：这条路径是读接口，宁可返回空列表也不能 NPE 成 500。
     */
    @GetMapping
    public List<DataSourceView> list() {
        Set<Long> visible = Objects.requireNonNullElse(
                visibilityService.visibleDatasourceIds(CurrentUser.id()), Set.of());
        return dataSourceService.listDatasources().stream()
                .filter(view -> visible.contains(view.id()))
                .toList();
    }

    @PostMapping
    public DataSourceView create(@RequestBody CreateDataSourceRequest request) {
        operatorGuard.requireConnectionOperatorForSystem(request.systemId());
        return dataSourceService.createDataSource(new DataSourceService.NewDataSource(
                request.systemId(),
                request.name(),
                request.dbName(),
                request.type(),
                request.host(),
                request.port(),
                request.charset(),
                request.timeZone()));
    }

    @PutMapping("/{id}")
    public DataSourceView update(@PathVariable long id, @RequestBody UpdateDataSourceRequest request) {
        operatorGuard.requireConnectionOperatorForDatasource(id);
        return dataSourceService.updateDataSource(id, request.name(), request.dbName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        operatorGuard.requireConnectionOperatorForDatasource(id);
        dataSourceService.deleteDataSource(id);
    }

    @PutMapping("/{id}/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateConnection(@PathVariable long id, @RequestBody ConnectionRequest request) {
        operatorGuard.requireConnectionOperatorForDatasource(id);
        dataSourceService.updateConnection(
                id, request.host(), request.port(), request.charset(), request.timeZone());
    }

    @PutMapping("/{id}/accounts/{accountType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveAccount(
            @PathVariable long id, @PathVariable String accountType, @RequestBody AccountRequest request) {
        operatorGuard.requireConnectionOperatorForDatasource(id);
        connAccountService.saveAccount(id, accountType, request.username(), request.password());
    }

    @DeleteMapping("/{id}/accounts/{accountType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable long id, @PathVariable String accountType) {
        operatorGuard.requireConnectionOperatorForDatasource(id);
        connAccountService.deleteAccount(id, accountType);
    }

    /** 新建数据源：归属系统、展示名、库名与连接参数。 */
    public record CreateDataSourceRequest(
            Long systemId,
            String name,
            String dbName,
            String type,
            String host,
            Integer port,
            String charset,
            String timeZone) {
    }

    /** 修改展示名与库名（连接参数走专用接口）。 */
    public record UpdateDataSourceRequest(String name, String dbName) {
    }

    /** 修改连接参数。 */
    public record ConnectionRequest(String host, Integer port, String charset, String timeZone) {
    }

    /** 配置账号；密码只在请求体中出现一次，入库前加密。 */
    public record AccountRequest(String username, String password) {

        @Override
        public String toString() {
            return "AccountRequest[username=" + username + ", password=***]";
        }
    }
}
