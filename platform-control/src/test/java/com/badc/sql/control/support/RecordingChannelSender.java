package com.badc.sql.control.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationTarget;
import com.badc.sql.control.notify.channel.ChannelSender;

/**
 * 可观察的渠道桩：记录"谁在哪个线程上被调用过"，并可按需失败。
 *
 * <p>两个用例需要它：
 * <ul>
 *   <li><b>异步性（R22）</b>：{@link #threadNames()} 记下每次 {@code send} 的
 *       {@code Thread.currentThread().getName()}。若投递被改回同步（去掉 {@code @Async} 或监听器直接在请求线程
 *       里跑），这里记到的就是 {@code http-nio-*}，断言立刻红。只看"HTTP 201"证明不了异步——同步投递同样
 *       返回 201，只是把渠道的耗时加到了研发的提交请求上。</li>
 *   <li><b>失败隔离</b>：{@link #failing} 为 true 时每次发送都抛异常，投递器按 {@code max-attempts}
 *       重试后落 {@code FAILED} 行；工单提交必须仍然 201。</li>
 * </ul>
 *
 * <p>实现 {@link ChannelSender} 意味着它会被自动收进 {@code NotificationDispatcher} 的
 * {@code List<ChannelSender>}——不需要替换任何既有 bean。{@link #channel()} 报 {@code IN_APP}，
 * 与真实的 {@code InAppSender} 同渠道，因此每个收件人会多出一行由本桩投递的记录；
 * 用例的断言都是"某类行存在/全都不含某片段"，不依赖具体行数。
 */
public class RecordingChannelSender implements ChannelSender {

    /** 每次 {@code send} 调用所在线程名，按调用顺序（跨线程追加，故同步包装）。 */
    private final List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

    /** 每次 {@code send} 的标题，按调用顺序；用来断言"消息里没有 SQL 正文"也覆盖到渠道入参。 */
    private final List<String> titles = Collections.synchronizedList(new ArrayList<>());

    /** 每次 {@code send} 的正文，按调用顺序。 */
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

    /** 尝试次数（含失败的尝试）：失败用例据此确认渠道真的被调用过，而不是断言在空转。 */
    private final AtomicInteger attempted = new AtomicInteger();

    /** 成功次数。 */
    private final AtomicInteger sent = new AtomicInteger();

    /** true 时每次发送都抛 {@link IllegalStateException}，模拟"渠道全挂"。 */
    public volatile boolean failing;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public void send(NotificationTarget target, String title, String body) {
        attempted.incrementAndGet();
        threadNames.add(Thread.currentThread().getName());
        titles.add(title);
        bodies.add(body);
        if (failing) {
            throw new IllegalStateException("测试桩：通知渠道不可用");
        }
        sent.incrementAndGet();
    }

    public List<String> threadNames() {
        synchronized (threadNames) {
            return List.copyOf(threadNames);
        }
    }

    public List<String> titles() {
        synchronized (titles) {
            return List.copyOf(titles);
        }
    }

    public List<String> bodies() {
        synchronized (bodies) {
            return List.copyOf(bodies);
        }
    }

    public int attempts() {
        return attempted.get();
    }

    public int sentCount() {
        return sent.get();
    }

    /** 每个用例前复位：失败标记与记录都不能跨用例泄漏。 */
    public void reset() {
        failing = false;
        attempted.set(0);
        sent.set(0);
        synchronized (threadNames) {
            threadNames.clear();
        }
        synchronized (titles) {
            titles.clear();
        }
        synchronized (bodies) {
            bodies.clear();
        }
    }
}
