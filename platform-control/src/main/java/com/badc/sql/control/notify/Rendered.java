package com.badc.sql.control.notify;

/**
 * 一次通知的渲染结果，落库前的一对快照（{@code notification.title} / {@code notification.body}）。
 *
 * <p>宽度由 {@link NotificationRenderer} 保证：标题 ≤ 200、正文 ≤ 2000 个码点（与
 * {@code notification} 表的列宽一致）。这里不再重复截断——渲染器是唯一生成 {@code Rendered} 的地方，
 * 两处都截会让"到底在哪一层被截的"说不清。改模板也不会改写历史通知：落库的是这份快照。
 *
 * @param title 标题（"【事件名】系统名"），非 {@code null}
 * @param body  正文（逐项事实，缺失项不出现），无内容时为空串而不是 {@code null}
 */
public record Rendered(String title, String body) {
}
