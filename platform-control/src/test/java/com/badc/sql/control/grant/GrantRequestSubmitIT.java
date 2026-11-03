package com.badc.sql.control.grant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.GrantRequestITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 提交申请（{@link GrantRequestService#submit}）与两条列表端点。
 *
 * <p>校验顺序本身就是契约，所以每个用例只验证"在当前数据下，第一条该失败的校验确实先失败"：
 * 例如"系统没有运维"那条必须真的走到步骤 3，而不是被步骤 2（理由为空）挡在前面。
 */
class GrantRequestSubmitIT extends GrantRequestITBase {

    @BeforeEach
    void clearRequests() {
        jdbc.update("delete from grant_request");
        // 本类有用例会造"已有授权"的前置条件；不清掉会污染后续用例（JUnit 不保证方法顺序）。
        jdbc.update("delete from user_module_grant where user_id = 1 and module_id in (202, 203)");
    }

    @Test
    void a_missing_module_is_a_404() {
        ResponseEntity<String> response = submitGrant(DEV_TOKEN, 999_999L, false, "要看订单数据");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("模块不存在");
    }

    @Test
    void a_blank_reason_is_refused() {
        ResponseEntity<String> response = submitGrant(DEV_TOKEN, MODULE_WRITE, false, "   ");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("申请理由不能为空");
        assertThat(jdbc.queryForObject("select count(*) from grant_request", Integer.class)).isZero();
    }

    @Test
    void a_system_without_operators_cannot_receive_requests() {
        // 系统 3（模块 301）没有任何 sys_system_operator 行：申请会永远停在 PENDING，必须在提交时拒绝。
        ResponseEntity<String> response = submitGrant(DEV_TOKEN, MODULE_ORPHAN, false, "要看报表数据");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("还没有运维");
        assertThat(jdbc.queryForObject("select count(*) from grant_request", Integer.class)).isZero();
    }

    @Test
    void a_second_pending_request_for_the_same_module_conflicts() {
        assertThat(submitGrant(DEV_TOKEN, MODULE_WRITE, false, "第一次申请").getStatusCode().value())
                .isEqualTo(201);

        ResponseEntity<String> again = submitGrant(DEV_TOKEN, MODULE_WRITE, false, "第二次申请");

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("待审批");
        assertThat(jdbc.queryForObject("select count(*) from grant_request", Integer.class)).isEqualTo(1);
    }

    @Test
    void a_module_you_already_have_cannot_be_requested_again() {
        // dev01 在夹具里已有模块 201 且 can_export = 1；这次不申请导出 → 纯重复申请。
        ResponseEntity<String> response = submitGrant(DEV_TOKEN, MODULE_GRANTED, false, "再要一次");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("已有该模块权限");
    }

    @Test
    void asking_only_for_export_on_a_module_you_have_is_allowed() {
        // 夹具里 dev01 对 201 已有导出权，所以这里换一个没有授权的模块先授权"仅查询"，再申请导出。
        jdbc.update("insert into user_module_grant (user_id, module_id, can_export, granted_by) "
                + "values (1, " + MODULE_WRITE + ", 0, 2)");

        ResponseEntity<String> response = submitGrant(DEV_TOKEN, MODULE_WRITE, true, "需要导出报表");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void a_successful_request_is_pending_audited_and_notifies_the_systems_operators() throws Exception {
        ResponseEntity<String> response = submitGrant(DEV_TOKEN, MODULE_WRITE, false, "排查线上订单重复");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.getBody());
        long id = body.path("id").asLong();
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(grantStatus(id)).isEqualTo("PENDING");

        assertThat(auditCount("GRANT_REQUESTED")).isPositive();
        // 收件人 = 系统 1 的运维（ops02）。申请人在等审批，运维要知道有活要干。
        awaitNotification(4L, "GRANT_REQUESTED");
    }

    @Test
    void mine_lists_only_my_requests() throws Exception {
        long mine = idOf(submitGrant(DEV_TOKEN, MODULE_WRITE, false, "我的申请"));
        idOf(submitGrant(OPS02_TOKEN, MODULE_WRITE, false, "运维自己的申请"));

        JsonNode devPage = objectMapper.readTree(getJson("/api/v1/grant-requests?scope=mine", DEV_TOKEN).getBody());
        assertThat(devPage.path("total").asLong()).isEqualTo(1);
        assertThat(devPage.path("items").get(0).path("id").asLong()).isEqualTo(mine);

        // ops02 自己那条不会出现在 dev01 的列表里（"我的申请"按 user_id 过滤，不是按系统）。
        JsonNode opsPage = objectMapper.readTree(getJson("/api/v1/grant-requests?scope=mine", OPS02_TOKEN).getBody());
        assertThat(opsPage.path("total").asLong()).isEqualTo(1);
    }

    @Test
    void pending_lists_only_requests_for_systems_i_operate() throws Exception {
        long forSystemOne = idOf(submitGrant(DEV_TOKEN, MODULE_WRITE, false, "系统 1 的申请"));
        idOf(submitGrant(DEV_TOKEN, MODULE_PAY, false, "系统 2 的申请"));

        // ops02 运维系统 1：只看到模块 202 那条。
        JsonNode ops02Page = objectMapper.readTree(
                getJson("/api/v1/grant-requests?scope=pending", OPS02_TOKEN).getBody());
        assertThat(ops02Page.path("total").asLong()).isEqualTo(1);
        assertThat(ops02Page.path("items").get(0).path("id").asLong()).isEqualTo(forSystemOne);

        // ops01 运维系统 2：只看到模块 203 那条。
        JsonNode ops01Page = objectMapper.readTree(
                getJson("/api/v1/grant-requests?scope=pending", OPS_TOKEN).getBody());
        assertThat(ops01Page.path("total").asLong()).isEqualTo(1);
        assertThat(ops01Page.path("items").get(0).path("moduleId").asLong()).isEqualTo(MODULE_PAY);
    }

    @Test
    void a_developer_sees_an_empty_pending_list_instead_of_403() throws Exception {
        // 研发不运维任何系统：语义上是"没有待我批的"，不是"你不许看"。
        ResponseEntity<String> response = getJson("/api/v1/grant-requests?scope=pending", DEV_TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.getBody()).path("total").asLong()).isZero();
    }

    @Test
    void an_unknown_scope_is_refused_instead_of_silently_defaulting() {
        ResponseEntity<String> response = getJson("/api/v1/grant-requests?scope=all", DEV_TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("scope");
    }
}
