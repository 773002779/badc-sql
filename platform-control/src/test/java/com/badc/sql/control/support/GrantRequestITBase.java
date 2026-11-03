package com.badc.sql.control.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 授权申请类集成测试基类：真实 HTTP + 真实元数据库（继承 {@link TicketITBase} 的夹具与身份）。
 *
 * <p>在工单夹具之上补三样东西，都是"提交校验顺序"所必需的对照组：
 * <ul>
 *   <li><b>模块 202</b>（系统 1）与 <b>模块 203</b>（系统 2）：dev01 尚未获得授权，能走到"申请成功"；</li>
 *   <li><b>系统 3 + 模块 301</b>：<b>没有任何运维归属</b>，验证"申请无人可批"在提交时就被拦下；</li>
 *   <li><b>admin_ops（id 6）</b>：ADMIN 角色<b>同时</b>被配成系统 1 的备运维。只有这种账号能证明
 *       "管理员不参与授权审批"这条判断真的生效——普通 ADMIN 不在任何运维归属里，会先被归属检查挡掉，
 *       测不到管理员那条分支。</li>
 * </ul>
 *
 * <p>夹具 ID 用固定值：断言里出现的 {@code 202/301} 就是这里插入的那几个模块，不必再查一次库。
 */
public abstract class GrantRequestITBase extends TicketITBase {

    /** 系统 1 的模块，dev01 无授权（成功申请的对照组）。 */
    protected static final long MODULE_WRITE = 202L;

    /** 系统 2 的模块，dev01 无授权（给"别的系统的待办"用）。 */
    protected static final long MODULE_PAY = 203L;

    /** 系统 3 的模块；系统 3 没有任何运维。 */
    protected static final long MODULE_ORPHAN = 301L;

    /** dev01 已授权（{@code can_export = 1}）的模块，见 {@code test-fixtures.sql}。 */
    protected static final long MODULE_GRANTED = 201L;

    protected static final long SYSTEM_WRITE = 1L;
    protected static final long SYSTEM_PAY = 2L;

    /** admin_ops：ADMIN 角色 + 系统 1 的备运维。 */
    protected static final long ADMIN_OPERATOR_ID = 6L;

    protected static final String ADMIN_OPS_TOKEN = JwtTestSupport.token(ADMIN_OPERATOR_ID, "admin_ops");

    /** 与夹具里其它用户同一串 BCrypt（明文 {@code Passw0rd!}）。 */
    private static final String PASSWORD_HASH =
            "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW";

    @BeforeAll
    void grantFixtures() {
        jdbc.update("insert into sys_system (id, name) values (3, '报表中心')");
        jdbc.update("""
                insert into sys_module (id, system_id, name) values
                    (202, 1, '订单写入'),
                    (203, 2, '支付查询'),
                    (301, 3, '报表查询')
                """);
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (6, 'admin_ops', ?, 'ADMIN', '管理员兼运维')
                """, PASSWORD_HASH);
        jdbc.update("""
                insert into sys_system_operator (id, system_id, user_id, operator_type)
                values (20, 1, 6, 'BACKUP')
                """);
    }

    /** 提交一条申请，返回原始响应（用例自己断言状态码）。 */
    protected ResponseEntity<String> submitGrant(String token, long moduleId, boolean canExport, String reason) {
        return postJson("/api/v1/grant-requests", token, grantBody(moduleId, canExport, reason));
    }

    /** 申请请求体。 */
    protected static Map<String, Object> grantBody(long moduleId, boolean canExport, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("moduleId", moduleId);
        body.put("canExport", canExport);
        body.put("reason", reason);
        return body;
    }

    /** 审批通过。 */
    protected ResponseEntity<String> approveGrant(String token, long requestId, String comment) {
        return postJson("/api/v1/grant-requests/" + requestId + "/approve", token, commentBody(comment));
    }

    /** 驳回。 */
    protected ResponseEntity<String> rejectGrant(String token, long requestId, String comment) {
        return postJson("/api/v1/grant-requests/" + requestId + "/reject", token, commentBody(comment));
    }

    /** 审批请求体。 */
    protected static Map<String, Object> commentBody(String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment);
        return body;
    }

    protected ResponseEntity<String> postJson(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 授权表里该人该模块的行数（0 或 1；{@code uk_user_module_grant} 保证不会更多）。 */
    protected int grantCount(long userId, long moduleId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from user_module_grant where user_id = ? and module_id = ?",
                Integer.class, userId, moduleId);
        return count == null ? 0 : count;
    }

    /** 授权表里该人该模块的 {@code can_export}；没有授权行时返回 {@code null}。 */
    protected Boolean canExportOf(long userId, long moduleId) {
        List<Boolean> values = jdbc.queryForList(
                "select can_export from user_module_grant where user_id = ? and module_id = ?",
                Boolean.class, userId, moduleId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 申请单当前状态；不存在时返回 {@code null}。 */
    protected String grantStatus(long requestId) {
        List<String> statuses = jdbc.queryForList(
                "select status from grant_request where id = ?", String.class, requestId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** 某类审计事件的行数。 */
    protected int auditCount(String eventType) {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_event where event_type = ?", Integer.class, eventType);
        return count == null ? 0 : count;
    }

    /**
     * 等待某个收件人收到某类通知（通知在事务提交后异步投递）。
     *
     * <p>用一个带截止时间的轮询，而不是"发完就断言"：投递发生在另一个线程，请求返回时可能还没落库。
     * 断言的是"最终会到"，不会因为调度慢而偶发失败；超过 {@code timeout} 才失败，并给出实际行数。
     */
    protected void awaitNotification(long recipientId, String eventType) {
        Duration timeout = Duration.ofSeconds(10);
        Instant deadline = Instant.now().plus(timeout);
        int seen = 0;
        while (Instant.now().isBefore(deadline)) {
            seen = notificationCount(recipientId, eventType);
            if (seen > 0) {
                return;
            }
            sleep(50);
        }
        assertThat(seen)
                .as("等待 %s 内收件人 %d 收到 %s 通知（实际 %d 条）", timeout, recipientId, eventType, seen)
                .isPositive();
    }

    protected int notificationCount(long recipientId, String eventType) {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification where recipient_id = ? and event_type = ?",
                Integer.class, recipientId, eventType);
        return count == null ? 0 : count;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待通知时被中断", e);
        }
    }
}
