package com.badc.sql.control.grant;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.security.CurrentUser;

/**
 * 查询「我有什么权限」。
 *
 * <p>只读接口：返回当前登录用户可见的模块与数据源 ID。前端据此渲染库列表，研发看不到未授权的库名
 * ——不是前端隐藏，而是后端根本不返回。
 *
 * <p>授权的写入（研发申请 / 运维审批）属于后续切片的工单模块，本切片不做。
 */
@RestController
@RequestMapping("/api/v1/grants")
public class GrantController {

    private final VisibilityService visibilityService;

    public GrantController(VisibilityService visibilityService) {
        this.visibilityService = visibilityService;
    }

    @GetMapping("/me")
    public MyGrants me() {
        Long userId = CurrentUser.id();
        return new MyGrants(
                List.copyOf(visibilityService.visibleModuleIds(userId)),
                List.copyOf(visibilityService.visibleDatasourceIds(userId)));
    }

    /** 当前用户的可见范围。 */
    public record MyGrants(List<Long> modules, List<Long> datasources) {
    }
}
