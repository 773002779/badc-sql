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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块维护与「模块-数据源」挂载。
 *
 * <p>写接口仅运维 / 管理员，且运维只能改<b>自己负责的系统</b>下的模块；挂载要求模块与数据源属于同一系统。
 */
@RestController
@RequestMapping("/api/v1/modules")
public class ModuleController {

    private final ModuleService moduleService;
    private final OperatorGuard operatorGuard;

    public ModuleController(ModuleService moduleService, OperatorGuard operatorGuard) {
        this.moduleService = moduleService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping
    public List<ModuleEntity> list(@RequestParam(name = "systemId", required = false) Long systemId) {
        return moduleService.listModules(systemId);
    }

    @PostMapping
    public ModuleEntity create(@RequestBody CreateModuleRequest request) {
        operatorGuard.requireStructureOperatorForSystem(request.systemId());
        return moduleService.createModule(request.systemId(), request.name());
    }

    @PutMapping("/{id}")
    public ModuleEntity rename(@PathVariable long id, @RequestBody RenameModuleRequest request) {
        operatorGuard.requireStructureOperatorForModule(id);
        return moduleService.renameModule(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        operatorGuard.requireStructureOperatorForModule(id);
        moduleService.deleteModule(id);
    }

    @PostMapping("/{id}/datasources/{datasourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mountDatasource(@PathVariable long id, @PathVariable long datasourceId) {
        operatorGuard.requireStructureOperatorForModule(id);
        moduleService.mountDatasource(id, datasourceId);
    }

    @DeleteMapping("/{id}/datasources/{datasourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmountDatasource(@PathVariable long id, @PathVariable long datasourceId) {
        operatorGuard.requireStructureOperatorForModule(id);
        moduleService.unmountDatasource(id, datasourceId);
    }

    /** 新建模块：归属系统 + 名称。 */
    public record CreateModuleRequest(Long systemId, String name) {
    }

    /** 重命名模块。 */
    public record RenameModuleRequest(String name) {
    }
}
