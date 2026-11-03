package com.badc.sql.control.notify;

import java.time.Clock;
import java.util.List;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.badc.sql.control.notify.channel.ChannelSender;

/**
 * 投递器：把一条 {@link NotificationCommand} 变成"每个收件人 × 每个渠道"的一行 {@code notification}，
 * 并逐行尝试发送。
 *
 * <h2>绝不抛异常</h2>
 * <p>调用方是 {@code @TransactionalEventListener(AFTER_COMMIT)} + 异步线程里的业务通知钩子：
 * 业务事务已经提交，通知失败不能、也无法回滚它。所以 {@link #dispatch} 的每一层都把异常收在内部——
 * 包括最外层的兜底 catch：{@code resolve} 连不上库、渲染出意外、落库报错，都只是"这次通知没发成"，
 * 不是"业务失败"。
 *
 * <h2>事件开关（R28）</h2>
 * <p>投递的第一步是 {@link NotificationSwitchService#isEnabled}：在 resolve 接收人、渲染、落库<b>之前</b>。
 * 事件关闭时直接返回，<b>不写任何 {@code notification} 行</b>——关闭是"这类事件不通知"，不是"投递失败"；
 * 写成 {@code SKIPPED} 会让运维把故意关掉看成投递失败，而且每关一个事件就多出成套的行。
 * 开关查询自身失败不阻断通知：记 warn 后按"默认开"继续投递，理由见 {@link #isEventEnabled}。
 *
 * <h2>失败隔离到"一个人 × 一个渠道"</h2>
 * <p>双层循环里每个组合独立 try/catch 并独立落库：邮件服务器挂了不影响企业微信，更不影响下一个收件人。
 * 这是 FR-13 的核心要求——一个人的通知失败不能顺带把别人也拖下水。落库在发送<b>之前</b>（{@code PENDING}），
 * 这样进程崩溃时留下的 PENDING 行是唯一的痕迹。
 *
 * <h2>收件人来源</h2>
 * <p>两条通道，顺序固定：{@link NotificationCommand#hasExplicitRecipients()} 为真时<b>直接用它</b>
 * （审批/执行结果要发给提交人，而提交人不在系统归属里），否则按
 * {@link NotificationRecipients#resolve} 解析归属。显式列表只在"归属表达不了"的地方使用——
 * 能由归属回答的（提交、L3 待终审、主备缺位兜底）一律走 resolve，避免两套收件人口径并存。
 *
 * <h2>四种结果</h2>
 * <ul>
 *   <li>渠道 {@link ChannelSender#isConfigured()} 为 false → {@code SKIPPED}（"渠道未启用"），一次都不发；</li>
 *   <li>发送成功 → {@code SENT}，{@code attempts} 记实际尝试次数，{@code sent_at} 取注入的 {@link Clock}；</li>
 *   <li>{@link NotificationSkippedException} → {@code SKIPPED}（原因用异常消息），<b>不重试</b>：
 *       缺邮箱/缺企微 userid 是确定性事实，重试一万次也一样；</li>
 *   <li>其它异常 → 按 {@code badcsql.notify.retry.max-attempts} 重试，第 n 次退避
 *       {@code base-backoff-millis * 2^(n-1)}；用尽后 {@code FAILED}，{@code last_error} 记脱敏后的原因。</li>
 * </ul>
 *
 * <h2>退避的可测性</h2>
 * <p>睡眠走注入的 {@link LongConsumer}：生产是 {@code Thread.sleep}，单测换成"记录睡了多久"。
 * 否则重试用例要真睡 500+1000ms，而且断言不了退避序列（只能断言"跑完了"）。
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** 渠道未配置时写进 {@code last_error} 的原因前缀。 */
    private static final String CHANNEL_DISABLED_REASON = "渠道未启用";

    /** 收件人缺少联系方式但异常没带消息时的兜底原因。 */
    private static final String CONTACT_MISSING_REASON = "收件人缺少该渠道的联系方式";

    /** 单次退避的硬上限：异步线程也是资源，指数退避不能退到"这个 worker 被占住半小时"。 */
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    /** 指数退避的最大位移，防止 {@code base << n} 溢出后变成奇怪的负数。 */
    private static final int MAX_BACKOFF_SHIFT = 20;

    /** 生产睡眠：真睡。被中断时恢复中断标记，由重试循环决定停止。 */
    private static final LongConsumer THREAD_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private final NotificationRecipients recipients;
    private final NotificationTargetStore targetStore;
    private final NotificationStore store;
    private final NotificationSwitchService switchService;
    private final List<ChannelSender> senders;
    private final NotificationProperties properties;
    private final Clock clock;
    private final LongConsumer sleeper;

    /** 生产构造器：睡眠就是 {@code Thread.sleep}。 */
    @Autowired
    public NotificationDispatcher(NotificationRecipients recipients, NotificationTargetStore targetStore,
            NotificationStore store, NotificationSwitchService switchService, List<ChannelSender> senders,
            NotificationProperties properties, Clock clock) {
        this(recipients, targetStore, store, switchService, senders, properties, clock, THREAD_SLEEPER);
    }

    /** 可注入时钟与睡眠的构造器：单测用它，避免真睡。 */
    NotificationDispatcher(NotificationRecipients recipients, NotificationTargetStore targetStore,
            NotificationStore store, NotificationSwitchService switchService, List<ChannelSender> senders,
            NotificationProperties properties, Clock clock, LongConsumer sleeper) {
        this.recipients = recipients;
        this.targetStore = targetStore;
        this.store = store;
        this.switchService = switchService;
        this.senders = senders == null ? List.of() : List.copyOf(senders);
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper == null ? THREAD_SLEEPER : sleeper;
    }

    /**
     * 投递一条通知。<b>永远不会抛异常</b>：失败只体现为 {@code notification} 行上的状态。
     *
     * @param command 事件与白名单事实；{@code null} 视为调用方 bug，记一条 warn 后返回（不抛）
     */
    public void dispatch(NotificationCommand command) {
        if (command == null) {
            log.warn("通知命令为 null，跳过投递");
            return;
        }
        try {
            if (!isEventEnabled(command)) {
                log.info("事件 {} 在系统 {} 已关闭，跳过投递", command.type(), command.systemId());
                return;
            }
            deliverAll(command);
        } catch (Exception e) {
            // 兜底：resolve / 渲染 / 加载收件人任何一步炸了，都不能回传给已提交的业务事务。
            log.warn("通知投递中止（事件 {}，系统 {}）：{}", command.type(), command.systemId(),
                    NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 事件开关判定，外面再包一层"失败即默认开"。
     *
     * <p><b>为什么失败时选"开"而不是"关"</b>：开关是"少发"的调节阀，而通知的价值是"该收到的人收到"。
     * 如果一次 {@code notification_switch} 查询抖动就按"关"处理，会<b>静默</b>吃掉本来该发的通知，
     * 且没有任何人能看出这个事件其实没被关——用户影响是漏发。按"开"处理最坏是关闭期间多发了通知
     * （用户可以忽略），而开关查询本身的错误已经由这条 warn 暴露出来，不会无声无息。
     *
     * <p>顺序（R28）：本方法在 resolve 接收人、渲染、落库<b>之前</b>调用。否则关闭的事件仍会写下
     * {@code PENDING} 行，运维看到的是"投递中/投递失败"，而不是"这个事件被关掉了"。
     */
    private boolean isEventEnabled(NotificationCommand command) {
        try {
            return switchService.isEnabled(command.type(), command.systemId());
        } catch (Exception e) {
            log.warn("通知开关查询失败（事件 {}，系统 {}），按默认开启继续投递：{}",
                    command.type(), command.systemId(), NotificationErrorText.sanitize(e));
            return true;
        }
    }

    private void deliverAll(NotificationCommand command) {
        Rendered rendered = NotificationRenderer.render(command);
        // 收件人来源只有两处，顺序固定：显式列表（"某个人无论如何都该收到"）优先，否则按系统归属解析。
        // 放在这里而不是更早，是为了让事件开关先短路——关掉的事件连收件人都不必查。
        List<Long> recipientIds = command.hasExplicitRecipients()
                ? command.explicitRecipients()
                : recipients.resolve(command.systemId(), command.type());
        if (recipientIds == null || recipientIds.isEmpty()) {
            // 没人可发是正常状态（系统还没配运维），不是失败：不写行、不抛异常。
            return;
        }
        for (NotificationTarget target : targetStore.load(recipientIds)) {
            for (ChannelSender sender : senders) {
                try {
                    deliverOne(command, rendered, target, sender);
                } catch (Exception e) {
                    // 最内层隔离：任何一个"人 × 渠道"的意外都不能中断整批。
                    log.warn("通知投递异常（收件人 {}，渠道 {}）：{}", target.userId(), channelOf(sender),
                            NotificationErrorText.sanitize(e));
                }
            }
        }
    }

    private void deliverOne(NotificationCommand command, Rendered rendered, NotificationTarget target,
            ChannelSender sender) {
        long id;
        try {
            id = store.insert(target.userId(), command.type(), command.systemId(), command.ticketId(),
                    command.grantRequestId(), sender.channel(), rendered.title(), rendered.body());
        } catch (Exception e) {
            // 连行都写不进去，没有可标记的对象；让其它收件人继续。
            log.warn("通知落库失败（收件人 {}，渠道 {}）：{}", target.userId(), channelOf(sender),
                    NotificationErrorText.sanitize(e));
            return;
        }

        boolean configured;
        try {
            configured = sender.isConfigured();
        } catch (Exception e) {
            // 渠道自己判断不了配置：记 FAILED 但 attempts 保持 0——一次发送都没发生，不能谎报尝试次数。
            log.warn("通知渠道配置判定失败（渠道 {}）：{}", channelOf(sender), NotificationErrorText.sanitize(e));
            markFailed(id, 0, e);
            return;
        }
        if (!configured) {
            markSkipped(id, CHANNEL_DISABLED_REASON + "：" + channelOf(sender));
            return;
        }

        sendWithRetries(id, rendered, target, sender);
    }

    /**
     * 发送并按 {@code max-attempts} 重试。
     *
     * <p>{@code attempts} 自增在发送<b>之前</b>：即使本轮在 {@code send} 内部抛错，这一轮也算"尝试过"，
     * 否则失败行的 {@code attempts} 会永远比实际少 1。
     */
    private void sendWithRetries(long id, Rendered rendered, NotificationTarget target, ChannelSender sender) {
        int maxAttempts = Math.max(1, properties.retry().maxAttempts());
        int attempts = 0;
        while (attempts < maxAttempts) {
            attempts++;
            try {
                sender.send(target, rendered.title(), rendered.body());
                markSent(id, attempts);
                return;
            } catch (NotificationSkippedException e) {
                // R18：跳过是终态，不重试、不算失败。原因来自异常消息（例如"收件人未填写邮箱"）。
                String reason = e.getMessage() == null || e.getMessage().isBlank()
                        ? CONTACT_MISSING_REASON
                        : e.getMessage();
                markSkipped(id, reason);
                return;
            } catch (Exception e) {
                if (attempts >= maxAttempts) {
                    markFailed(id, attempts, e);
                    return;
                }
                backoff(attempts);
                if (Thread.currentThread().isInterrupted()) {
                    // 投递线程被中断（多半是关机/取消）：别再睡第二次，把当前进度如实记下。
                    markFailed(id, attempts, new IllegalStateException("投递线程被中断，停止重试"));
                    return;
                }
            }
        }
    }

    /** 第 {@code attemptsSoFar} 次失败后的退避：{@code base * 2^(attemptsSoFar-1)}，带上限。 */
    private void backoff(int attemptsSoFar) {
        long base = Math.max(0, properties.retry().baseBackoffMillis());
        long millis = Math.min((long) base << Math.min(attemptsSoFar - 1, MAX_BACKOFF_SHIFT), MAX_BACKOFF_MILLIS);
        if (millis <= 0) {
            return;
        }
        try {
            sleeper.accept(millis);
        } catch (RuntimeException e) {
            // 替换进来的 sleeper 出错不该中断整批投递（生产实现不会抛，这里只是兜底）。
            log.warn("通知重试退避失败：{}", NotificationErrorText.sanitize(e));
        }
    }

    private void markSent(long id, int attempts) {
        try {
            store.markSent(id, attempts, clock.instant());
        } catch (Exception e) {
            log.warn("通知 {} 回写 SENT 失败：{}", id, NotificationErrorText.sanitize(e));
        }
    }

    private void markFailed(long id, int attempts, Throwable error) {
        try {
            store.markFailed(id, attempts, truncateError(error));
        } catch (Exception e) {
            log.warn("通知 {} 回写 FAILED 失败：{}", id, NotificationErrorText.sanitize(e));
        }
    }

    private void markSkipped(long id, String reason) {
        try {
            // 跳过原因同样落 last_error、同样给研发看：与 FAILED 用同一套脱敏与截断。
            store.markSkipped(id, truncateText(reason));
        } catch (Exception e) {
            log.warn("通知 {} 回写 SKIPPED 失败：{}", id, NotificationErrorText.sanitize(e));
        }
    }

    /** 异常 → 脱敏 + 压到 {@link NotificationErrorText#DISPATCHER_MAX_CHARS}，绝不让口令进 last_error。 */
    private static String truncateError(Throwable error) {
        return NotificationErrorText.truncate(
                NotificationErrorText.sanitize(error), NotificationErrorText.DISPATCHER_MAX_CHARS);
    }

    /** 纯文本（SKIPPED 的原因）→ 同一套脱敏与上限。 */
    private static String truncateText(String text) {
        return NotificationErrorText.truncate(
                NotificationErrorText.sanitizeText(text), NotificationErrorText.DISPATCHER_MAX_CHARS);
    }

    private static String channelOf(ChannelSender sender) {
        try {
            return String.valueOf(sender.channel());
        } catch (Exception e) {
            return "<unknown>";
        }
    }
}
