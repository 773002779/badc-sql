package com.badc.sql.control.org;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.query.DatasourceCapabilityService;

/**
 * 数据源（{@code datasource}）管理。
 *
 * <p>对外只返回 {@link DataSourceView}；连接参数（host/port/charset/timeZone）只能通过专用接口由运维修改，
 * 账号与密码由 {@link ConnAccountService} 管理。
 */
@Service
public class DataSourceService {

    private final DataSourceMapper dataSourceMapper;
    private final SystemMapper systemMapper;
    private final ConnAccountMapper connAccountMapper;
    private final DatasourceCapabilityService capabilityService;

    public DataSourceService(
            DataSourceMapper dataSourceMapper,
            SystemMapper systemMapper,
            ConnAccountMapper connAccountMapper,
            DatasourceCapabilityService capabilityService) {
        this.dataSourceMapper = dataSourceMapper;
        this.systemMapper = systemMapper;
        this.connAccountMapper = connAccountMapper;
        this.capabilityService = capabilityService;
    }

    public List<DataSourceView> listDatasources() {
        return dataSourceMapper.findAll().stream().map(DataSourceService::toView).toList();
    }

    /** 单个数据源的安全视图；不存在时 404。可见性由调用方（controller）先行判定。 */
    public DataSourceView viewOf(long id) {
        return dataSourceMapper.findById(id)
                .map(DataSourceService::toView)
                .orElseThrow(() -> OrgRules.notFound("数据源", id));
    }

    public DataSourceView createDataSource(NewDataSource request) {
        if (request == null || request.systemId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "所属系统不能为空");
        }
        systemMapper.findById(request.systemId())
                .orElseThrow(() -> OrgRules.notFound("系统", request.systemId()));

        DataSourceEntity entity = new DataSourceEntity(
                null,
                request.systemId(),
                OrgRules.requireName(request.name(), "数据源名称"),
                OrgRules.requireText(request.dbName(), "库名", 128),
                OrgRules.normalizeDbType(request.type()),
                OrgRules.requireText(request.host(), "主机", 255),
                OrgRules.requirePort(request.port()),
                OrgRules.optionalText(request.charset(), OrgRules.DEFAULT_CHARSET, "字符集", 32),
                OrgRules.optionalText(request.timeZone(), OrgRules.DEFAULT_TIME_ZONE, "连接时区", 64));

        long id = dataSourceMapper.insert(entity);
        return toView(new DataSourceEntity(
                id,
                entity.systemId(),
                entity.name(),
                entity.dbName(),
                entity.dbType(),
                entity.host(),
                entity.port(),
                entity.charset(),
                entity.timeZone()));
    }

    public DataSourceView updateDataSource(long id, String name, String dbName) {
        DataSourceEntity existing = dataSourceMapper.findById(id)
                .orElseThrow(() -> OrgRules.notFound("数据源", id));
        String normalizedName = OrgRules.requireName(name, "数据源名称");
        String normalizedDbName = OrgRules.requireText(dbName, "库名", 128);
        dataSourceMapper.updateMetadata(id, normalizedName, normalizedDbName);
        return toView(new DataSourceEntity(
                existing.id(),
                existing.systemId(),
                normalizedName,
                normalizedDbName,
                existing.dbType(),
                existing.host(),
                existing.port(),
                existing.charset(),
                existing.timeZone()));
    }

    public void updateConnection(long id, String host, Integer port, String charset, String timeZone) {
        dataSourceMapper.findById(id).orElseThrow(() -> OrgRules.notFound("数据源", id));
        dataSourceMapper.updateConnection(
                id,
                OrgRules.requireText(host, "主机", 255),
                OrgRules.requirePort(port),
                OrgRules.optionalText(charset, OrgRules.DEFAULT_CHARSET, "字符集", 32),
                OrgRules.optionalText(timeZone, OrgRules.DEFAULT_TIME_ZONE, "连接时区", 64));
        // 指向变了，之前探测到的版本 / binlog / 在线 DDL 结论不再成立——必须重新探测，
        // 否则把库从 5.7 换到 8.0 之后，正确的 CTE 还会被"版本不支持"拒掉最长 10 分钟。
        capabilityService.evict(id);
    }

    @Transactional
    public void deleteDataSource(long id) {
        dataSourceMapper.findById(id).orElseThrow(() -> OrgRules.notFound("数据源", id));
        connAccountMapper.deleteByDatasource(id);
        dataSourceMapper.deleteModuleLinks(id);
        dataSourceMapper.delete(id);
    }

    private static DataSourceView toView(DataSourceEntity entity) {
        return new DataSourceView(
                entity.id(), entity.name(), entity.dbName(), entity.systemId(), entity.dbType());
    }

    /** 新建数据源的入参；连接参数在此一次性写入。 */
    public record NewDataSource(
            Long systemId,
            String name,
            String dbName,
            String type,
            String host,
            Integer port,
            String charset,
            String timeZone) {
    }
}
