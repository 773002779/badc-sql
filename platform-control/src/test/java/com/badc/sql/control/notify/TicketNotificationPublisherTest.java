package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import com.badc.sql.control.ticket.TicketEntity;
import com.badc.sql.control.ticket.TicketMapper;

/**
 * 短链（R36）：{@code link-base} 配置了才生成完整地址，为空时干脆不填这个键。
 *
 * <p>为什么用单元测试而不是端到端：{@code link-base} 是<b>应用级配置</b>，在同一个 Spring 上下文里改它
 * 只能靠 {@code @TestPropertySource} 再起一个上下文——代价高、还会把"默认空"这条路径挤掉。这里直接构造
 * 发布器，两种配置各验一次，<b>两条分支都被钉住</b>：
 * <ul>
 *   <li>配了基地址 → {@code {base}/tickets/{id}}（末尾多余的斜杠要被吃掉）；</li>
 *   <li>没配 → 没有 {@code link} 键（不发一个点不开的相对路径）。</li>
 * </ul>
 * 端到端侧另有一条断言（{@code TicketNotificationIT} 的 R11 用例）确认默认配置下正文里没有"短链"这一行。
 */
class TicketNotificationPublisherTest {

    private static final long TICKET_ID = 42L;

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationRecipients recipients = mock(NotificationRecipients.class);
    private final TicketMapper ticketMapper = mock(TicketMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @BeforeEach
    void stubTicketAndNames() {
        when(ticketMapper.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));
        when(recipients.resolve(anyLong(), any(NotificationEventType.class))).thenReturn(List.of(4L));
        // 系统/模块/数据源/提交人 与 statement_type 都走同一个重载，统一返回一个可读的名字即可。
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of("订单中心"));
    }

    @Test
    void a_configured_link_base_produces_a_full_ticket_url() {
        publisher("https://console.example.com/").submitted(TICKET_ID);

        assertThat(capturedCommand().facts())
                .containsEntry("link", "https://console.example.com/tickets/42");
    }

    @Test
    void an_empty_link_base_leaves_the_link_key_out_entirely() {
        publisher(NotificationProperties.DEFAULT_LINK_BASE).submitted(TICKET_ID);

        assertThat(capturedCommand().facts())
                .as("基地址为空时不能发相对路径——那在企微里点不开，也不是给人读的地址")
                .doesNotContainKey("link");
    }

    private TicketNotificationPublisher publisher(String linkBase) {
        return new TicketNotificationPublisher(events, recipients, ticketMapper,
                new NotificationProperties(NotificationProperties.Wecom.defaults(),
                        NotificationProperties.Mail.defaults(),
                        NotificationProperties.Retry.defaults(),
                        linkBase),
                jdbc);
    }

    private NotificationCommand capturedCommand() {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).publishEvent(captor.capture());
        return captor.getValue().command();
    }

    /** 一张最小可用的工单：只有 id/system/module/datasource/submitter/risk/title 有值。 */
    private static TicketEntity ticket() {
        return new TicketEntity(
                TICKET_ID, 1L, 201L, 101L, "NORMAL", "REVIEWING_L1", "L1",
                null, null, "订单数据变更", null, null, null, null, true, null,
                1L, null, null, null, null, null, null, null);
    }
}
