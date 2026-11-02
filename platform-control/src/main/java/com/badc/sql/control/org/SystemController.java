package com.badc.sql.control.org;

import java.util.List;

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

import com.badc.sql.control.security.CurrentUser;

/**
 * 系统建档与维护。
 *
 * <p>读接口对所有已登录用户开放，<b>故意不按可见性过滤</b>：§4.4② 要求研发「选 系统 → 模块 → 申请
 * 权限」，能看见尚未授权的系统才谈得上申请；而 §4.3-8「最小可见」约束的是库（数据源），不是系统名。
 * 数据源的可见性另有闸，见 {@code VisibilityService}。改这里之前先读 {@code SystemService} 的类注释。
 *
 * <p>写接口仅运维 / 管理员，研发一律 403。其中<b>建档仅管理员</b>（§4.4），改名 / 删除还要求该系统
 * 在该运维的归属范围内。
 */
@RestController
@RequestMapping("/api/v1/systems")
public class SystemController {

    private final SystemService systemService;
    private final OperatorGuard operatorGuard;

    public SystemController(SystemService systemService, OperatorGuard operatorGuard) {
        this.systemService = systemService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping
    public List<SystemEntity> list() {
        return systemService.listSystems();
    }

    @PostMapping
    public SystemEntity create(@RequestBody SystemRequest request) {
        operatorGuard.requireAdmin();
        // 建档者同时成为该系统的 PRIMARY 运维，否则建完系统再建数据源的人自己看不到它（B8）。
        return systemService.createSystem(request.name(), CurrentUser.id());
    }

    @PutMapping("/{id}")
    public SystemEntity rename(@PathVariable long id, @RequestBody SystemRequest request) {
        operatorGuard.requireStructureOperatorForSystem(id);
        return systemService.renameSystem(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        operatorGuard.requireStructureOperatorForSystem(id);
        systemService.deleteSystem(id);
    }

    /** 系统名称。 */
    public record SystemRequest(String name) {
    }
}
