package com.badc.sql.control.audit;

/**
 * 一条待写入的审计事件。
 *
 * @param payloadJson 事件载荷（JSON 文本）；写入前会做键排序规范化，保证同样内容得到同样的哈希
 */
public record AuditCommand(
        String eventType,
        Long operatorId,
        String execIdentity,
        Long datasourceId,
        String action,
        String objectId,
        String payloadJson) {
}
