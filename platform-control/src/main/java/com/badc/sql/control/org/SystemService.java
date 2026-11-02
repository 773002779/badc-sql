package com.badc.sql.control.org;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 系统（{@code sys_system}）管理。
 *
 * <h2>读列表是「组织目录」，不是「授权范围」</h2>
 * <p>{@link #listSystems()} 返回<b>全部系统</b>，不按调用者的可见性过滤。这是有意为之，不是漏做：
 * <ul>
 *   <li>§4.3-8「最小可见」约束的是<b>库</b>——『研发只能看到已授权模块下的数据源，未授权的库不在搜索、
 *       收藏、历史中返回』。系统名与模块名是组织目录，既不是凭证也不是数据；</li>
 *   <li>§4.4② 要求研发「选 系统 → 模块 → 申请权限」，而申请的前提就是能看见<b>自己还没有权限</b>的
 *       系统。按可见性过滤这张列表，等于把申请入口变成空列表；</li>
 *   <li>真正的执行点在数据源：{@link com.badc.sql.control.grant.VisibilityService} 决定哪些
 *       {@code datasource} 可见，查询 / 导出 / 工单 / 历史都按它收敛。知道系统名既看不到库名，
 *       也拿不到任何数据。</li>
 * </ul>
 *
 * <p>若将来确实要隐藏组织架构，必须<b>同时</b>给申请流程提供一份只含 {@code {id, name}} 的目录，
 * 否则 §4.4② 会失去数据来源。改动前请先读 {@code DataSourceApiIT} 里那两条"目录保持开放"的用例。
 *
 * <p>建档时把<b>创建者登记为该系统的 PRIMARY 运维</b>（{@code sys_system_operator}）：没有归属行，
 * 这个系统就只有管理员能改（{@code OperatorGuard} 的归属闸）、它的数据源只有管理员可见，而且它的
 * L3 工单永远凑不齐审批人。归属因此和系统名一起落在同一个事务里，不会出现无主的系统。
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
