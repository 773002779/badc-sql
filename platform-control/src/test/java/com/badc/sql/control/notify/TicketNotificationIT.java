package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.control.support.RecordingChannelSender;
import com.badc.sql.control.support.TicketITBase;
import com.badc.sql.control.support.TicketNotificationTestConfig;

/**
 * 通知接线的端到端用例：真实 HTTP + 真实元数据库 + 真实异步线程池。
 *
 * <h2>怎么等异步（本类最重要的基础设施）</h2>
 * <p>投递挂在 {@link TransactionalEventListener}（{@link TransactionPhase#AFTER_COMMIT}）+
 * {@code @Async} 上，业务接口返回时通知<b>可能还没跑完</b>。这里不用 {@code Thread.sleep(固定毫秒)}：
 * 那会让用例在慢机器上偶发假红、在快机器上偶发假绿（睡得比投递久，断言的是"等待"而不是"实现"）。
 *
 * <p>同步手段是 {@link AsyncConfig#NOTIFICATION_EXECUTOR} 线程池的<b>已完成任务数</b>：
 * <ol>
 *   <li>发请求前记下 {@code completedTaskCount}（{@link #notificationTaskMark()}，先等线程池空下来）；</li>
 *   <li>请求返回后轮询到"新增完成数 ≥ 期望值 且 无活动线程"（{@link #awaitNotificationTasks}），
 *       最多 10 秒，超时直接把当前队列长度与 {@code notification} 行数打进失败信息。</li>
 * </ol>
 * 为什么这不是"碰运气"：
 * <ul>
 *   <li>任务是在<b>请求返回之前</b>提交的（{@code publishEvent} 在业务方法内同步执行，{@code @Async}
 *       的提交动作发生在事务提交回调里，都在 HTTP 响应之前），所以请求返回后"计数会涨"是确定事件；</li>
 *   <li>{@code dispatch} 在任务体内<b>同步</b>完成 resolve/渲染/落库/发送，任务完成 ⇒ 该事件的行已经写完了；</li>
 *   <li>轮询只决定"何时检查"，不决定"检查什么"——超时是失败，不是跳过。</li>
 * </ul>
 *
 * <p><b>零行断言（事件开关）</b>也走同一条路径：等到"这个事件确实被投递过"（任务完成数 +1）之后再断言
 * 表里零行。只等一个 {@code sleep} 再数零行是恒真的——投递还没开始，数出来永远是零。
 *
 * <h2>为什么必须真实异步（R22）</h2>
 * <p>{@link RecordingChannelSender#threadNames()} 记录每次 {@code send} 所在线程。若有人去掉
 * {@code @Async}（或把监听器改成同步），投递会跑在 {@code http-nio-*} 上——本类
 * {@code the_delivery_runs_off_the_http_request_thread} 立刻红。"提交返回 201"证明不了异步：
 * 同步投递同样 201，只是把渠道耗时压到了研发的请求上。
 */
@Import(TicketNotificationTestConfig.class)
class TicketNotificationIT extends TicketITBase {

    /** 系统 1 的主运维（ticket-fixtures.sql 的用户 4）。 */
    private static final long PRIMARY_OPS_ID = 4L;

    /** 系统 1 的组长（ticket-fixtures.sql 的用户 5）。 */
    private static final long LEAD_ID = 5L;

    /** 管理员：只用来写通知开关（开关接口本身的权限不属本任务）。 */
    private static final long ADMIN_ID = 3L;

    private static final String L1_SQL = "update t_order set a = 1 where id = 1";
    private static final String L1_ROLLBACK_SQL = "update t_order set a = 0 where id = 1";
    private static final String L3_SQL = "drop table t_order";
    private static final String L3_ROLLBACK_PLAN = "将从快照重建表结构";

    /** 等待通知的上限：投递重试最坏是 3 次尝试 + 500/1000ms 退避，10 秒留足余量。 */
    private static final long AWAIT_NANOS = 10_000_000_000L;

    /** 轮询间隔。 */
    private static final long POLL_MILLIS = 50L;

    @Autowired
    @Qualifier(AsyncConfig.NOTIFICATION_EXECUTOR)
    private ThreadPoolTaskExecutor notificationExecutor;

    @Autowired
    private RecordingChannelSender recorder;

    @Autowired
    private NotificationSwitchService switches;

    @BeforeEach
    void resetNotificationState() {
        // 先把上一个用例遗留的投递跑完：否则它的完成数会被算进本用例的"期望任务数"里，等待变成空转。
        awaitNotificationIdle();
        jdbc.update("delete from notification");
        jdbc.update("delete from notification_switch");
        jdbc.update("delete from datasource_exec_lock");
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket_review");
        jdbc.update("delete from ticket");
        jdbc.update("delete from audit_event");
        // 归属：R16 用例会删掉主备运维行，每个用例前恢复夹具状态（id 10 = 主运维，11 = 组长）。
        jdbc.update("delete from sys_system_operator where system_id = 1");
        jdbc.update("""
                insert into sys_system_operator (id, system_id, user_id, operator_type)
                values (10, 1, 4, 'PRIMARY')
                """);
        jdbc.update("""
                insert into sys_system_operator (id, system_id, user_id, operator_type)
                values (11, 1, 5, 'LEAD')
                """);
        recorder.reset();
    }

    // ------------------------------------------------------------------ R11：不含 SQL 正文

    /**
     * <b>R11 的承重断言</b>：工单语句里的字面量绝不能出现在任何一条通知里。
     *
     * <p>为什么必须走完整的提交接口：Task 2 的同名用例因为"从未喂入 SQL"而恒真，被 R11 删掉。
     * 只有把带字面量的语句真的提交、等异步投递落库，再去读 {@code notification.title/body}，
     * 才能证明"平台没有把工单内容塞进白名单字段"。
     */
    @Test
    void a_ticket_literal_never_reaches_the_notification_title_or_body() {
        String literal = "身份证110101199001011234";
        String sql = "update t_order set note = '" + literal + "' where id = 1";

        long mark = notificationTaskMark();
        long id = submitTicket(sql, null, "update t_order set note = null where id = 1");
        awaitNotificationTasks(mark, 1);

        List<Row> rows = notificationsOf(id);
        assertThat(rows)
                .as("通知必须真的落库：没有行的断言是恒真的（这正是 R11 删掉 Task 2 那条用例的原因）")
                .isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.title()).doesNotContain(literal).doesNotContain("身份证").doesNotContain("note");
            assertThat(row.body()).doesNotContain(literal).doesNotContain("身份证").doesNotContain("note");
            // "note" 是列名，literal 是值；整条语句更不该出现。
            assertThat(row.body()).doesNotContain("update t_order").doesNotContain("set note");
        });
        // 反证：确实通知的是"这张单已提交"，而不是一屏空白。
        assertThat(rows).extracting(Row::eventType).containsOnly("TICKET_SUBMITTED");
        assertThat(rows).extracting(Row::recipientId).containsOnly(PRIMARY_OPS_ID);
    }

    // ------------------------------------------------------------------ R22：不阻塞业务线程

    /**
     * <b>R22 的承重断言</b>：投递发生在通知线程池上，而不是 HTTP 请求线程上。
     *
     * <p>只断言"提交返回 201"证明不了异步——同步投递一样返回 201。这里读的是渠道桩记录的线程名：
     * 一旦有人去掉 {@code @Async}（或让监听器在发布点同步执行），投递就会落在 {@code http-nio-*} 上。
     */
    @Test
    void the_delivery_runs_off_the_http_request_thread() {
        long mark = notificationTaskMark();
        long id = submitTicket(L1_SQL, null, L1_ROLLBACK_SQL);

        // 等"渠道真的被调用过"这个可观察事实，而不是等通知线程池的任务数：投递若被改回同步，
        // 它根本不会进线程池，等任务数只会在超时处失败，掩盖掉真正要断言的线程名。
        awaitFirstDelivery();

        List<String> names = recorder.threadNames();
        assertThat(names).as("渠道必须真的被调用过，否则线程名断言是空转").isNotEmpty();
        assertThat(names).allSatisfy(name -> assertThat(name)
                .as("投递线程不能是 HTTP 请求线程（%s）", Thread.currentThread().getName())
                .startsWith(AsyncConfig.THREAD_NAME_PREFIX)
                .doesNotContain("http-nio")
                .doesNotContain("Test worker"));
        assertThat(notificationsOf(id)).isNotEmpty();
        // 收尾：等这一批投递跑完，别把活动任务留给下一个用例。
        awaitNotificationTasks(mark, 1);
    }

    // ------------------------------------------------------------------ R16：主备运维缺位的兜底

    /**
     * <b>R16 的承重断言</b>：系统没有主/备运维归属时，提交通知改发组长，事件换成
     * {@link NotificationEventType#TICKET_LEAD_FALLBACK}；恢复归属后变回
     * {@link NotificationEventType#TICKET_SUBMITTED}。
     *
     * <p>两段都要断言：只验"缺位时发给了组长"无法排除"永远发给组长"，只验"恢复后发给运维"无法排除
     * "缺位时谁也不发"。
     */
    @Test
    void a_system_without_primary_or_backup_falls_back_to_the_lead() {
        jdbc.update("""
                delete from sys_system_operator where system_id = 1 and operator_type in ('PRIMARY', 'BACKUP')
                """);
        try {
            long mark = notificationTaskMark();
            long fallbackTicket = submitTicket(L1_SQL, null, L1_ROLLBACK_SQL);
            awaitNotificationTasks(mark, 1);

            List<Row> fallbackRows = notificationsOf(fallbackTicket);
            assertThat(fallbackRows)
                    .as("R16：主备运维缺位时必须真的落一条通知（零行说明兜底路径没跑或收件人解析为空）")
                    .isNotEmpty();
            assertThat(fallbackRows)
                    .as("主备运维缺位时必须改发 TICKET_LEAD_FALLBACK，而不是普通 TICKET_SUBMITTED")
                    .extracting(Row::eventType)
                    .containsOnly("TICKET_LEAD_FALLBACK");
            assertThat(fallbackRows)
                    .as("兜底通知的收件人是组长")
                    .extracting(Row::recipientId)
                    .containsOnly(LEAD_ID);
            assertThat(fallbackRows.get(0).title())
                    .as("文案要点明主备运维缺位，而不是普通「待审核」")
                    .contains("主备运维缺位")
                    .contains("代为审批");
        } finally {
            jdbc.update("""
                    insert into sys_system_operator (id, system_id, user_id, operator_type)
                    values (10, 1, 4, 'PRIMARY')
                    """);
        }

        long mark = notificationTaskMark();
        long normalTicket = submitTicket(L1_SQL, null, L1_ROLLBACK_SQL);
        awaitNotificationTasks(mark, 1);

        List<Row> normalRows = notificationsOf(normalTicket);
        assertThat(normalRows).isNotEmpty();
        assertThat(normalRows)
                .as("恢复归属后必须变回普通提交事件")
                .extracting(Row::eventType)
                .containsOnly("TICKET_SUBMITTED");
        assertThat(normalRows).extracting(Row::recipientId).containsOnly(PRIMARY_OPS_ID);
    }

    // ------------------------------------------------------------------ R15：L3 不在一审前打扰组长

    /**
     * <b>R15 的承重断言</b>：L3 工单提交时<b>不</b>通知组长；一级审核通过、转入 {@code REVIEWING_L2} 后
     * 才出现 {@link NotificationEventType#TICKET_L3_PENDING_LEAD}，且收件人含组长。
     *
     * <p>提交阶段只断言"没有这条事件"是不够的（监听器没跑也会没有），所以同时断言提交事件本身落库了：
     * 证明通知链路在工作，缺的确实是"L3 待终审"这一条。
     */
    @Test
    void an_l3_ticket_notifies_the_lead_only_after_the_first_level_approval() {
        long mark = notificationTaskMark();
        long id = submitTicket(L3_SQL, L3_ROLLBACK_PLAN, null);
        awaitNotificationTasks(mark, 1);

        List<Row> submitted = notificationsOf(id);
        assertThat(submitted).as("提交通知必须存在，否则「没有 L3 待终审」是空转").isNotEmpty();
        assertThat(submitted)
                .as("R15：L3 工单提交时不通知组长")
                .extracting(Row::eventType)
                .doesNotContain("TICKET_L3_PENDING_LEAD");
        assertThat(submitted).extracting(Row::eventType).contains("TICKET_SUBMITTED");

        long approveMark = notificationTaskMark();
        ResponseEntity<String> approved = approve(OPS02_TOKEN, id, "一级通过");
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("REVIEWING_L2");
        awaitNotificationTasks(approveMark, 1);

        List<Row> pendingLead = rowsOfType(id, "TICKET_L3_PENDING_LEAD");
        assertThat(pendingLead)
                .as("一级通过、转入 REVIEWING_L2 后才该通知组长终审")
                .isNotEmpty();
        assertThat(pendingLead)
                .as("收件人必须含组长（同时仍含主/备运维：待终审是「追加组长」）")
                .extracting(Row::recipientId)
                .contains(LEAD_ID, PRIMARY_OPS_ID);
        assertThat(pendingLead.get(0).title()).contains("待终审");
    }

    // ------------------------------------------------------------------ 失败隔离

    /**
     * 渠道全部抛异常时，提交仍然 201、工单仍然落库。
     *
     * <p>断言"渠道真的被调用并失败"（{@code FAILED} 行）同样重要：若桩没被调用，这条用例只证明了
     * "没有通知时提交能成功"，与隔离性无关。
     */
    @Test
    void a_broken_channel_never_fails_the_submission() {
        recorder.failing = true;

        long mark = notificationTaskMark();
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body(L1_SQL, null, L1_ROLLBACK_SQL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(response);
        awaitNotificationTasks(mark, 1);

        assertThat(jdbc.queryForObject("select count(*) from ticket where id = ?", Integer.class, id))
                .as("通知失败不能让工单消失")
                .isEqualTo(1);
        assertThat(jdbc.queryForList("select status from notification where ticket_id = ?", String.class, id))
                .as("失败必须落成 FAILED 行，而不是被静默吞掉")
                .contains("FAILED");
    }

    // ------------------------------------------------------------------ 事件开关

    /**
     * 事件开关关闭 {@code TICKET_SUBMITTED} 后，提交<b>一行通知都不写</b>（连 {@code SKIPPED} 也不写）。
     *
     * <p>两段对比：关闭后零行、重新打开后又有行。只验"关闭后零行"无法排除"通知链路根本没通"——
     * 而"等到投递任务确实跑完（完成数 +1）再数零行"排除了"还没来得及写"这种假绿。
     */
    @Test
    void a_disabled_event_writes_no_notification_rows() {
        switches.setGlobal(NotificationEventType.TICKET_SUBMITTED, false, ADMIN_ID);

        long mark = notificationTaskMark();
        long disabledTicket = submitTicket(L1_SQL, null, L1_ROLLBACK_SQL);
        awaitNotificationTasks(mark, 1);

        assertThat(notificationsOf(disabledTicket))
                .as("关闭的事件不写任何 notification 行（也不写 SKIPPED：关闭不是投递失败）")
                .isEmpty();

        switches.setGlobal(NotificationEventType.TICKET_SUBMITTED, true, ADMIN_ID);

        long again = notificationTaskMark();
        long enabledTicket = submitTicket(L1_SQL, null, L1_ROLLBACK_SQL);
        awaitNotificationTasks(again, 1);

        assertThat(notificationsOf(enabledTicket))
                .as("重新打开后必须恢复投递，证明零行是开关造成的")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ 执行终态

    /** 执行成功 → {@link NotificationEventType#TICKET_EXECUTED}，且消息里没有任何错误文本/SQL。 */
    @Test
    void a_successful_execution_is_notified_without_any_error_text() {
        stub.executeResults = List.of(new ExecuteResult(true, 2L, 7L, null, null));
        long id = approvedTicket();

        long mark = notificationTaskMark();
        requestExecution(DEV_TOKEN, id);
        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        awaitNotificationTasks(mark, 1);

        List<Row> rows = rowsOfType(id, "TICKET_EXECUTED");
        assertThat(rows).as("执行成功必须通知").isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.body()).doesNotContain("update t_order").doesNotContain("t_order");
            assertThat(row.body()).doesNotContain("dml_user").doesNotContain("dml-pass");
        });
    }

    /**
     * 执行失败 → {@link NotificationEventType#TICKET_EXEC_FAILED}，且<b>错误文本绝不进消息</b>
     * （执行错误可能含 SQL 片段与凭证形态，只能点短链进详情页看）。
     */
    @Test
    void a_failed_execution_is_notified_without_the_error_text() {
        stub.failOnStatementIndex = Set.of(0);
        long id = approvedTicket();

        long mark = notificationTaskMark();
        requestExecution(DEV_TOKEN, id);
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        awaitNotificationTasks(mark, 1);

        List<Row> rows = rowsOfType(id, "TICKET_EXEC_FAILED");
        assertThat(rows).as("执行失败必须通知").isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.title())
                    .doesNotContain("模拟的语句执行失败")
                    .doesNotContain("表不存在");
            assertThat(row.body())
                    .doesNotContain("模拟的语句执行失败")
                    .doesNotContain("表不存在")
                    .doesNotContain("update t_order");
        });
        // 断言用的错误文本确实存在于执行记录里：证明"消息里没有它"不是因为系统里根本没有这个错误。
        assertThat(jdbc.queryForObject("""
                select error_message from ticket_statement where ticket_id = ? and seq_no = 1
                """, String.class, id)).contains("模拟的语句执行失败");
    }

    // ------------------------------------------------------------------ 提交 / 审批 / 执行辅助

    private long submitTicket(String sql, String rollbackPlan, String rollbackSql) {
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body(sql, rollbackPlan, rollbackSql));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    private ResponseEntity<String> approve(String token, long ticketId, String comment) {
        return postJson(token, "/api/v1/tickets/" + ticketId + "/approve", "{\"comment\":\"" + comment + "\"}");
    }

    /** 提交 → 一级审批 → 执行前风险确认，返回可执行的工单 ID（与 TicketExecutionIT 同一套真实链路）。 */
    private long approvedTicket() {
        long id = submitTicket(L1_SQL, null, L1_ROLLBACK_SQL);
        assertThat(approve(OPS02_TOKEN, id, "通过").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(postJson(DEV_TOKEN, "/api/v1/tickets/" + id + "/risk-ack", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return id;
    }

    private void requestExecution(String token, long ticketId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange("/api/v1/tickets/" + ticketId + "/execute",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /**
     * 轮询到执行终态，并且执行锁已释放。
     *
     * <p>等锁不是多余的：锁在任务体的 {@code finally} 里释放，而终态通知在执行体末尾发布。只等状态会在
     * "状态已写、通知还没发"的窗口里返回，后面的 {@code awaitNotificationTasks} 就会拿到 0 个任务。
     */
    private String awaitTerminal(long ticketId) {
        long deadline = System.nanoTime() + AWAIT_NANOS;
        while (System.nanoTime() < deadline) {
            String current = status(ticketId);
            if (!"EXECUTING".equals(current) && !"APPROVED".equals(current) && !lockHeld(ticketId)) {
                return current;
            }
            sleepPoll();
        }
        throw new AssertionError("工单 " + ticketId + " 未在 10s 内进入终态，当前=" + status(ticketId));
    }

    private boolean lockHeld(long ticketId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from datasource_exec_lock where ticket_id = ?", Integer.class, ticketId);
        return count != null && count > 0;
    }

    private ResponseEntity<String> postJson(String token, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    // ------------------------------------------------------------------ 异步等待（本类的同步手段）

    /**
     * 记下"此刻通知线程池已完成的任务数"，作为一次操作的基准。
     *
     * <p>调用前先等线程池空下来：上一个用例遗留的任务若在本方法之后才完成，会被算进本次的期望数，
     * 等待就会提前返回（假绿）。
     */
    private long notificationTaskMark() {
        awaitNotificationIdle();
        return pool().getCompletedTaskCount();
    }

    /** 等通知线程池完成至少 {@code expected} 个新任务。超时是失败，不是跳过。 */
    private void awaitNotificationTasks(long mark, int expected) {
        long deadline = System.nanoTime() + AWAIT_NANOS;
        long done = 0;
        while (System.nanoTime() < deadline) {
            ThreadPoolExecutor pool = pool();
            done = pool.getCompletedTaskCount() - mark;
            if (done >= expected && pool.getActiveCount() == 0) {
                return;
            }
            sleepPoll();
        }
        throw new AssertionError("通知任务未在 " + (AWAIT_NANOS / 1_000_000_000L) + "s 内完成：已完成 "
                + done + "/" + expected + "，活动线程 " + pool().getActiveCount()
                + "，队列 " + pool().getQueue().size()
                + "，notification 行数 " + countNotifications());
    }

    /** 等渠道桩第一次被调用（轮询，最多 10s）。超时是失败：投递压根没发生。 */
    private void awaitFirstDelivery() {
        long deadline = System.nanoTime() + AWAIT_NANOS;
        while (System.nanoTime() < deadline) {
            if (!recorder.threadNames().isEmpty()) {
                return;
            }
            sleepPoll();
        }
        throw new AssertionError("渠道在 10s 内没有被调用过：notification 行数 " + countNotifications());
    }

    /** 等线程池既没有活动线程也没有排队任务。 */
    private void awaitNotificationIdle() {
        long deadline = System.nanoTime() + AWAIT_NANOS;
        while (System.nanoTime() < deadline) {
            ThreadPoolExecutor pool = pool();
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                return;
            }
            sleepPoll();
        }
        throw new AssertionError("通知线程池未在 " + (AWAIT_NANOS / 1_000_000_000L)
                + "s 内空闲：活动线程 " + pool().getActiveCount() + "，队列 " + pool().getQueue().size());
    }

    private ThreadPoolExecutor pool() {
        return notificationExecutor.getThreadPoolExecutor();
    }

    private static void sleepPoll() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待通知时被中断", e);
        }
    }

    // ------------------------------------------------------------------ 查询辅助

    private int countNotifications() {
        Integer count = jdbc.queryForObject("select count(*) from notification", Integer.class);
        return count == null ? 0 : count;
    }

    private List<Row> notificationsOf(long ticketId) {
        return jdbc.query("""
                select event_type, recipient_id, status, channel, title, body
                  from notification
                 where ticket_id = ?
                 order by id
                """,
                (rs, rowNum) -> new Row(
                        rs.getString("event_type"),
                        rs.getLong("recipient_id"),
                        rs.getString("status"),
                        rs.getString("channel"),
                        rs.getString("title"),
                        rs.getString("body")),
                ticketId);
    }

    private List<Row> rowsOfType(long ticketId, String eventType) {
        return notificationsOf(ticketId).stream().filter(row -> eventType.equals(row.eventType())).toList();
    }

    /**
     * {@code notification} 的一行（只取本类用到的列）。
     *
     * @param eventType   事件类型（落库值）
     * @param recipientId 收件人用户 ID
     * @param status      投递状态
     * @param channel     渠道
     * @param title       标题快照
     * @param body        正文快照
     */
    private record Row(String eventType, long recipientId, String status, String channel,
            String title, String body) {
    }
}
