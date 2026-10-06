package com.github.lechandonga.incidentremediationorchestratorjava.store;

import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditEventType;
import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditLog;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维护窗口服务。目标处于维护窗口内时，告警必须被明确跳过，
 * 跳过原因可与抑制、目标繁忙区分。
 */
@Component
public class MaintenanceWindowService {
    private final Clock clock;
    private final AuditLog auditLog;
    /** target -> 维护截止时间 */
    private final ConcurrentHashMap<String, Instant> untilByTarget = new ConcurrentHashMap<>();

    public MaintenanceWindowService(Clock clock, AuditLog auditLog) {
        this.clock = clock;
        this.auditLog = auditLog;
    }

    /** 进入维护窗口，durationMillis 后自动到期。 */
    public void begin(String target, long durationMillis) {
        if (target == null || target.isBlank() || durationMillis <= 0) {
            throw new IllegalArgumentException("target 不能为空且 durationMillis 必须为正数");
        }
        Instant until = clock.instant().plusMillis(durationMillis);
        untilByTarget.put(target, until);
        auditLog.append(AuditEventType.MAINTENANCE_WINDOW_CHANGED, null, target,
                "进入维护窗口，截止 " + until);
    }

    /** 提前结束维护窗口。 */
    public void end(String target) {
        Instant removed = untilByTarget.remove(target);
        auditLog.append(AuditEventType.MAINTENANCE_WINDOW_CHANGED, null, target,
                removed == null ? "结束维护窗口（此前不存在）" : "提前结束维护窗口，原截止 " + removed);
    }

    public boolean isInMaintenance(String target) {
        Instant until = untilByTarget.get(target);
        if (until == null) {
            return false;
        }
        if (clock.instant().isBefore(until)) {
            return true;
        }
        untilByTarget.remove(target, until);
        return false;
    }

    public Set<String> activeTargets() {
        return untilByTarget.keySet().stream().filter(this::isInMaintenance).collect(java.util.stream.Collectors.toSet());
    }

    public void clear() {
        untilByTarget.clear();
    }
}
