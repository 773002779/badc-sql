package com.badc.sql.control.notify;

import java.util.Map;
import java.util.Set;

/**
 * 一条待渲染的通知：事件类型 + 关联的业务主键 + <b>白名单事实</b>。
 *
 * <h2>为什么不带 SQL</h2>
 * <p>FR-13 要求通知里<b>不得出现 SQL 正文</b>。如果只靠"调用方自觉"，总有人会把整条语句塞进
 * {@code facts} 的某个值里（"把语句一起发给他更方便"），而一旦落库就再也收不回来——通知是发给
 * 没有该库权限的人看的。所以这里把"能携带什么"做成结构约束：键只能是 {@link #ALLOWED_FACT_KEYS}
 * 里的 10 个，其余键在构造时当场抛 {@link IllegalArgumentException}，异常消息带上那个非法键名，
 * 让写错的地方一眼可见。
 *
 * <p>白名单只约束<b>键</b>，不约束值的内容：{@code reason} 是审核人写的自由文本，本身就可能提到列名
 * 甚至片段。这是刻意接受的——拒单理由不写清楚，收件人无法判断要改什么。真正的硬约束是"SQL 正文
 * 没有进入 {@code facts} 的通道"。
 *
 * <h2>不可变</h2>
 * <p>{@code facts} 在构造时复制成不可变 Map，{@code null} 值与白名单之外的键一样被拒绝（"缺失"
 * 用不传这个键表达，而不是传 {@code null}）。空 Map 合法：不是每个事件都有全部事实，渲染时缺失项跳过。
 *
 * @param type           事件类型，非 {@code null}
 * @param systemId       相关业务系统 ID；{@code long} 而非 {@code Long}，所以"系统为空"在类型上就不可表达
 * @param ticketId       相关工单 ID；非工单事件为 {@code null}
 * @param grantRequestId 相关授权申请 ID；非授权事件为 {@code null}
 * @param facts          白名单事实，非 {@code null}，值非 {@code null}
 */
public record NotificationCommand(
        NotificationEventType type,
        long systemId,
        Long ticketId,
        Long grantRequestId,
        Map<String, String> facts) {

    /**
     * 允许出现在 {@code facts} 里的键。
     *
     * <p>全部是与 SQL 无关的元数据：系统、模块、数据源、语句类型（{@code UPDATE} 这种动词，不是语句原文）、
     * 风险等级、影响行数预估、提交人、工单号、短链、原因。新增键必须同时改
     * {@link NotificationRenderer} 的渲染顺序与 {@code notification.title}/{@code body} 的容量预算，
     * 不能只在这里加一行。
     */
    public static final Set<String> ALLOWED_FACT_KEYS = Set.of(
            "system",
            "module",
            "datasource",
            "statementType",
            "riskLevel",
            "estimatedRows",
            "submitter",
            "ticketNo",
            "link",
            "reason");

    public NotificationCommand {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空：通知必须知道自己在通知什么事件");
        }
        if (facts == null) {
            throw new IllegalArgumentException("facts 不能为 null；没有事实时传空 Map");
        }
        // 先自己校验再 Map.copyOf：copyOf 遇到 null 只会给一个没有键名的 NPE，排查时看不出是哪一项。
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            String key = fact.getKey();
            if (key == null || !ALLOWED_FACT_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "facts 含白名单之外的键：" + key + "；只允许 " + ALLOWED_FACT_KEYS);
            }
            if (fact.getValue() == null) {
                throw new IllegalArgumentException(
                        "facts 的键 " + key + " 的值为 null；缺失项请直接不传这个键");
            }
        }
        facts = Map.copyOf(facts);
    }
}
