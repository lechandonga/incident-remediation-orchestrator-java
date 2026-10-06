package com.github.lechandonga.incidentremediationorchestratorjava.audit;

import java.time.Instant;

/**
 * 不可变审计事件。{@code target} 允许为空（如针对未知目标的拒绝），
 * 其余字段均使用稳定的字符串/枚举，保证可以被序列化与回放。
 */
public record AuditEvent(
        long sequence,
        Instant timestamp,
        AuditEventType type,
        String remediationId,
        String target,
        String detail
) {
    public String toLogLine() {
        return String.format("[AUDIT #%d %s] type=%s remediation=%s target=%s detail=%s",
                sequence, timestamp, type, remediationId, target, detail);
    }
}
