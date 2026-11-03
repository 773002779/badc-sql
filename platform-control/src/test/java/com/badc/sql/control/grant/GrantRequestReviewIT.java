package com.badc.sql.control.grant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.GrantRequestITBase;

/**
 * 审批与驳回（{@link GrantRequestService#approve} / {@link GrantRequestService#reject}）。
 *
 * <p>每条用例都同时断言两件事：HTTP 结论，以及<b>授权表到底有没有多一行</b>。只断言 403 是不够的——
 * 自我提权最危险的表现是"返回了错误，但授权已经写进去了"。
 */
class GrantRequestReviewIT extends GrantRequestITBase {

    @BeforeEach
    void clearRequests() {
        jdbc.update("delete from grant_request");
        jdbc.update("delete from user_module_grant where user_id = 1 and module_id in (202, 203)");
    }

    /** 以 dev01 身份申请一个系统 1 的模块，返回申请单 ID。 */
    private long pendingRequestForSystemOne() {
        ResponseEntity<String> response = submitGrant(DEV_TOKEN, MODULE_WRITE, true, "排查线上订单");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return idOf(response);
    }

    @Test
    void an_applicant_cannot_approve_their_own_request() {
        long id = pendingRequestForSystemOne();

        ResponseEntity<String> response = approveGrant(DEV_TOKEN, id, "自己批自己");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("不能审批自己的申请");
        assertThat(grantCount(1L, MODULE_WRITE)).isZero();
        assertThat(grantStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void approving_twice_is_a_conflict_and_writes_one_grant_only() {
        long id = pendingRequestForSystemOne();

        assertThat(approveGrant(OPS02_TOKEN, id, "同意").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> again = approveGrant(OPS02_TOKEN, id, "再批一次");

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(grantCount(1L, MODULE_WRITE)).isEqualTo(1);
        assertThat(grantStatus(id)).isEqualTo("APPROVED");
    }

    @Test
    void a_non_operator_cannot_approve() {
        long id = pendingRequestForSystemOne();

        // ops01 是系统 2 的主运维，不负责系统 1。
        ResponseEntity<String> response = approveGrant(OPS_TOKEN, id, "越权审批");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("只能审批自己负责的系统");
        assertThat(grantCount(1L, MODULE_WRITE)).isZero();
    }

    @Test
    void an_admin_who_happens_to_be_an_operator_still_cannot_approve() {
        long id = pendingRequestForSystemOne();

        // admin_ops 同时是系统 1 的备运维：归属检查会通过，必须由"管理员不参与授权审批"拦住。
        ResponseEntity<String> response = approveGrant(ADMIN_OPS_TOKEN, id, "管理员来批");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("管理员不参与授权审批");
        assertThat(grantCount(1L, MODULE_WRITE)).isZero();
    }

    @Test
    void approving_a_query_only_request_never_downgrades_an_existing_export_grant() {
        // 前置：dev01 对模块 203 已有导出权，而库里有条"仅查询"的历史申请（申请单早于授权创建）。
        jdbc.update("insert into user_module_grant (user_id, module_id, can_export, granted_by) "
                + "values (1, 203, 1, 2)");
        jdbc.update("insert into grant_request (user_id, module_id, can_export, reason, status) "
                + "values (1, 203, 0, '只需要查询', 'PENDING')");
        long id = jdbc.queryForObject("select max(id) from grant_request", Long.class);
        assertThat(canExportOf(1L, MODULE_PAY)).isTrue();

        ResponseEntity<String> response = approveGrant(OPS_TOKEN, id, "同意");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // upsert 用 greatest(旧值, 新值)：批"仅查询"不能把已有的导出权抹掉。
        assertThat(canExportOf(1L, MODULE_PAY)).isTrue();
        assertThat(grantCount(1L, MODULE_PAY)).isEqualTo(1);
    }

    @Test
    void approving_writes_the_grant_and_notifies_the_applicant() {
        long id = pendingRequestForSystemOne();

        ResponseEntity<String> response = approveGrant(OPS02_TOKEN, id, "已确认影响范围");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("APPROVED");
        assertThat(grantCount(1L, MODULE_WRITE)).isEqualTo(1);
        assertThat(canExportOf(1L, MODULE_WRITE)).isTrue();
        assertThat(auditCount("GRANT_APPROVED")).isPositive();
        // 申请人在等结果：这条必须发给他，而不是按系统归属发给刚点完按钮的运维。
        awaitNotification(1L, "GRANT_APPROVED");
    }

    @Test
    void rejecting_requires_a_comment_and_writes_no_grant() {
        long id = pendingRequestForSystemOne();

        ResponseEntity<String> response = rejectGrant(OPS02_TOKEN, id, "  ");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("驳回必须填写审批意见");
        assertThat(grantCount(1L, MODULE_WRITE)).isZero();
        assertThat(grantStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void rejecting_with_a_comment_marks_rejected_and_notifies_the_applicant() {
        long id = pendingRequestForSystemOne();

        ResponseEntity<String> response = rejectGrant(OPS02_TOKEN, id, "只有查询需求，请走只读库");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("REJECTED");
        assertThat(grantStatus(id)).isEqualTo("REJECTED");
        assertThat(grantCount(1L, MODULE_WRITE)).isZero();
        assertThat(auditCount("GRANT_REJECTED")).isPositive();
        awaitNotification(1L, "GRANT_REJECTED");
    }

    @Test
    void approving_a_request_whose_module_was_deleted_is_a_404() {
        long id = pendingRequestForSystemOne();
        jdbc.update("delete from sys_module where id = ?", MODULE_WRITE);

        try {
            ResponseEntity<String> response = approveGrant(OPS02_TOKEN, id, "模块没了");
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(grantCount(1L, MODULE_WRITE)).isZero();
        } finally {
            jdbc.update("insert into sys_module (id, system_id, name) values (?, 1, '订单写入')",
                    MODULE_WRITE);
        }
    }
}
