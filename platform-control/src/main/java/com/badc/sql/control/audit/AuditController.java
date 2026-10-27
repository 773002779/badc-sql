package com.badc.sql.control.audit;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;
import com.badc.sql.control.user.UserRoleService;

/**
 * 审计查询与链校验。
 *
 * <p>只有运维组长与管理员可以校验/查看审计链——研发看到全公司的操作记录没有业务价值，反而是信息泄露面。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final Set<String> ALLOWED_ROLES = Set.of("OPS_LEAD", "ADMIN");

    private final AuditService auditService;
    private final UserRoleService userRoleService;

    public AuditController(AuditService auditService, UserRoleService userRoleService) {
        this.auditService = auditService;
        this.userRoleService = userRoleService;
    }

    @GetMapping("/verify")
    public AuditService.VerifyResult verify() {
        requireAuditReader();
        return auditService.verify();
    }

    @GetMapping("/tail")
    public List<Map<String, Object>> tail(@RequestParam(defaultValue = "50") int limit) {
        requireAuditReader();
        return auditService.tail(Math.min(Math.max(limit, 1), 500));
    }

    private void requireAuditReader() {
        String role = userRoleService.roleOf(CurrentUser.id());
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅运维组长或管理员可查看审计");
        }
    }
}
