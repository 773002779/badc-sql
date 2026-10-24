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

/**
 * 系统建档与维护。
 *
 * <p>读接口对所有已登录用户开放（系统名本身不是敏感信息）；写接口仅运维 / 管理员，研发一律 403。
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
        operatorGuard.requireOperator();
        return systemService.createSystem(request.name());
    }

    @PutMapping("/{id}")
    public SystemEntity rename(@PathVariable long id, @RequestBody SystemRequest request) {
        operatorGuard.requireOperator();
        return systemService.renameSystem(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        operatorGuard.requireOperator();
        systemService.deleteSystem(id);
    }

    /** 系统名称。 */
    public record SystemRequest(String name) {
    }
}
