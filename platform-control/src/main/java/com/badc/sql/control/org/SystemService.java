package com.badc.sql.control.org;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 系统（{@code sys_system}）管理。
 *
 * <p>建档时把<b>创建者登记为该系统的 PRIMARY 运维</b>（{@code sys_system_operator}）：列表按可见性
 * 过滤（§4.3-8）之后，不登记归属的话，刚建完系统再建数据源的人自己反而看不到它。
 *
 * <p>删除系统前要求其下没有模块与数据源，避免一次删除级联掉大量业务元数据；归属行随之清理。
 */
@Service
public class SystemService {

    /** 运维归属类型：一个系统的主运维（与 {@code V2__system_operator.sql} 的 CHECK 约束一致）。 */
    private static final String PRIMARY_OPERATOR = "PRIMARY";

    private final SystemMapper systemMapper;

    public SystemService(SystemMapper systemMapper) {
        this.systemMapper = systemMapper;
    }

    public List<SystemEntity> listSystems() {
        return systemMapper.findAll();
    }

    /**
     * 建档并登记归属。
     *
     * <p>创建者 ID 由调用方传入（控制器用 {@code CurrentUser.id()}），而不是在服务里读安全上下文：
     * 归属是这条记录的组成部分，和系统名一起决定"谁能看见它"，放在同一事务里才不会出现无主的系统。
     */
    @Transactional
    public SystemEntity createSystem(String name, long creatorId) {
        String normalized = OrgRules.requireName(name, "系统名称");
        long id = systemMapper.insert(normalized);
        systemMapper.insertOperator(id, creatorId, PRIMARY_OPERATOR);
        return new SystemEntity(id, normalized);
    }

    public SystemEntity renameSystem(long id, String name) {
        systemMapper.findById(id).orElseThrow(() -> OrgRules.notFound("系统", id));
        String normalized = OrgRules.requireName(name, "系统名称");
        systemMapper.update(id, normalized);
        return new SystemEntity(id, normalized);
    }

    @Transactional
    public void deleteSystem(long id) {
        systemMapper.findById(id).orElseThrow(() -> OrgRules.notFound("系统", id));
        if (systemMapper.countModules(id) > 0 || systemMapper.countDatasources(id) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "系统下仍有模块或数据源，不能删除: " + id);
        }
        systemMapper.deleteOperators(id);
        systemMapper.delete(id);
    }
}
