package com.badc.sql.control.support;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.security.CurrentUser;

/**
 * 仅测试用的受保护探针端点：本切片还没有其他 {@code /api/**} 业务端点，用它验证
 * 「JWT 过滤器 → SecurityContext → {@link CurrentUser#id()}」整条身份链路。
 *
 * <p>位于测试源集，不会随应用发布。
 */
@RestController
public class MeProbeController {

    @GetMapping("/api/v1/_probe/me")
    public Map<String, Long> me() {
        return Map.of("userId", CurrentUser.id());
    }
}
