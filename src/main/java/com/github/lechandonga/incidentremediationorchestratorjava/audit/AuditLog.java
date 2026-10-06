package com.github.lechandonga.incidentremediationorchestratorjava.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地审计日志：内存事件存储 + 顺序号 + 落日志（可改为写文件/消息队列）。
 * 所有接入决策与状态变更必须经过这里，不允许绕过。
 */
@Component
public class AuditLog {
    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private final AtomicLong sequence = new AtomicLong(0);
    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    public AuditEvent append(AuditEventType type, String remediationId, String target, String detail) {
        AuditEvent event = new AuditEvent(
                sequence.incrementAndGet(), Instant.now(), type, remediationId, target, detail);
        events.add(event);
        log.info(event.toLogLine());
        return event;
    }

    public List<AuditEvent> all() {
        return List.copyOf(events);
    }

    public List<AuditEvent> byRemediation(String remediationId) {
        return events.stream().filter(e -> remediationId.equals(e.remediationId())).toList();
    }

    public void clear() {
        events.clear();
        sequence.set(0);
    }
}
