package com.badc.sql.control.query;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.security.CurrentUser;
import com.badc.sql.control.user.UserRoleService;

/**
 * 查询历史。可见范围按角色收敛（研发只看自己、运维看所辖系统、管理员看全部）。
 */
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final QueryHistoryService historyService;
    private final UserRoleService userRoleService;

    public HistoryController(QueryHistoryService historyService, UserRoleService userRoleService) {
        this.historyService = historyService;
        this.userRoleService = userRoleService;
    }

    @GetMapping
    public Map<String, Object> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = CurrentUser.id();
        return historyService.page(userId, userRoleService.roleOf(userId), page, size);
    }
}
