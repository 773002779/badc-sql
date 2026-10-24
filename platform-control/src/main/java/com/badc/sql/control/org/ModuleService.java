package com.badc.sql.control.org;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 模块（{@code sys_module}）管理，以及模块与数据源的挂载关系。
 *
 * <p>模块与数据源必须属于同一系统：跨系统共享不走挂载，而由归属系统建模块授权给对方研发。
 * 删除模块会在同一事务内清掉 {@code module_datasource} 与 {@code user_module_grant}，
 * 不留下孤儿行。
 */
@Service
public class ModuleService {

    private final ModuleMapper moduleMapper;
    private final SystemMapper systemMapper;
    private final DataSourceMapper dataSourceMapper;

    public ModuleService(
            ModuleMapper moduleMapper, SystemMapper systemMapper, DataSourceMapper dataSourceMapper) {
        this.moduleMapper = moduleMapper;
        this.systemMapper = systemMapper;
        this.dataSourceMapper = dataSourceMapper;
    }

    public List<ModuleEntity> listModules(Long systemId) {
        return systemId == null ? moduleMapper.findAll() : moduleMapper.findAllBySystemId(systemId);
    }

    public ModuleEntity createModule(Long systemId, String name) {
        if (systemId == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "所属系统不能为空");
        }
        systemMapper.findById(systemId).orElseThrow(() -> OrgRules.notFound("系统", systemId));
        String normalized = OrgRules.requireName(name, "模块名称");
        long id = moduleMapper.insert(systemId, normalized);
        return new ModuleEntity(id, systemId, normalized);
    }

    public ModuleEntity renameModule(long id, String name) {
        ModuleEntity existing = moduleMapper.findById(id).orElseThrow(() -> OrgRules.notFound("模块", id));
        String normalized = OrgRules.requireName(name, "模块名称");
        moduleMapper.update(id, normalized);
        return new ModuleEntity(existing.id(), existing.systemId(), normalized);
    }

    @Transactional
    public void deleteModule(long id) {
        moduleMapper.findById(id).orElseThrow(() -> OrgRules.notFound("模块", id));
        moduleMapper.deleteDatasourceLinks(id);
        moduleMapper.deleteGrants(id);
        moduleMapper.delete(id);
    }

    public void mountDatasource(long moduleId, long datasourceId) {
        ModuleEntity module = moduleMapper.findById(moduleId)
                .orElseThrow(() -> OrgRules.notFound("模块", moduleId));
        DataSourceEntity datasource = dataSourceMapper.findById(datasourceId)
                .orElseThrow(() -> OrgRules.notFound("数据源", datasourceId));
        if (!module.systemId().equals(datasource.systemId())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "数据源 " + datasourceId + " 与模块 " + moduleId + " 不属于同一系统");
        }
        moduleMapper.mount(moduleId, datasourceId);
    }

    public void unmountDatasource(long moduleId, long datasourceId) {
        moduleMapper.findById(moduleId).orElseThrow(() -> OrgRules.notFound("模块", moduleId));
        moduleMapper.unmount(moduleId, datasourceId);
    }
}
