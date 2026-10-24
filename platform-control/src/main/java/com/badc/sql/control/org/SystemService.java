package com.badc.sql.control.org;

import java.util.List;

import org.springframework.stereotype.Service;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 系统（{@code sys_system}）管理。
 *
 * <p>删除系统前要求其下没有模块与数据源，避免一次删除级联掉大量业务元数据。
 */
@Service
public class SystemService {

    private final SystemMapper systemMapper;

    public SystemService(SystemMapper systemMapper) {
        this.systemMapper = systemMapper;
    }

    public List<SystemEntity> listSystems() {
        return systemMapper.findAll();
    }

    public SystemEntity createSystem(String name) {
        String normalized = OrgRules.requireName(name, "系统名称");
        long id = systemMapper.insert(normalized);
        return new SystemEntity(id, normalized);
    }

    public SystemEntity renameSystem(long id, String name) {
        systemMapper.findById(id).orElseThrow(() -> OrgRules.notFound("系统", id));
        String normalized = OrgRules.requireName(name, "系统名称");
        systemMapper.update(id, normalized);
        return new SystemEntity(id, normalized);
    }

    public void deleteSystem(long id) {
        systemMapper.findById(id).orElseThrow(() -> OrgRules.notFound("系统", id));
        if (systemMapper.countModules(id) > 0 || systemMapper.countDatasources(id) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "系统下仍有模块或数据源，不能删除: " + id);
        }
        systemMapper.delete(id);
    }
}
