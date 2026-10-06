package com.github.lechandonga.incidentremediationorchestratorjava.store;

import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditEventType;
import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抑制跟踪器。键为 {@code alertKey + "|" + target}（同一条告警），
 * 在抑制窗口内重复上报返回既有 remediationId，不得重复触发处置。
 */
@Component
public class SuppressionTracker {
    private final long suppressionWindowMillis;
    private final Clock clock;
    private final AuditLog auditLog;
    private final ConcurrentHashMap<String, SuppressionEntry> entries = new ConcurrentHashMap<>();

    public SuppressionTracker(
            @Value("${orchestrator.suppression-window-millis:60000}") long suppressionWindowMillis,
            Clock clock, AuditLog auditLog) {
        this.suppressionWindowMillis = suppressionWindowMillis;
        this.clock = clock;
        this.auditLog = auditLog;
    }

    private static String key(String alertKey, String target) {
        return alertKey + "|" + target;
    }

    /**
     * 原子地登记一条新告警。窗口内已存在则返回既有条目（抑制命中），
     * 否则登记本次并返回 empty。
     */
    public Optional<SuppressionEntry> registerIfAbsent(String alertKey, String target) {
        String k = key(alertKey, target);
        SuppressionEntry created = new SuppressionEntry(null, clock.instant());
        for (;;) {
            SuppressionEntry existing = entries.putIfAbsent(k, created);
            if (existing == null) {
                return Optional.empty();
            }
            if (existing.suppressedUntil(clock.instant(), suppressionWindowMillis)) {
                return Optional.of(existing);
            }
            // 窗口已过：CAS 替换，并发的重复上报只会有一个胜出
            if (entries.replace(k, existing, created)) {
                return Optional.empty();
            }
        }
    }

    public long windowMillis() {
        return suppressionWindowMillis;
    }

    public void clear() {
        entries.clear();
    }

    /** 显式登记一个抑制键（供重放/内部建单后使用）。 */
    public void register(String alertKey, String target, String remediationId) {
        entries.put(key(alertKey, target), new SuppressionEntry(remediationId, clock.instant()));
    }

    /** 移除抑制占位（建单失败/被拒绝时回滚，避免抑制掉后续合法上报）。 */
    public void remove(String alertKey, String target) {
        entries.remove(key(alertKey, target));
    }

    public record SuppressionEntry(String remediationId, Instant firstSeenAt) {
        boolean suppressedUntil(Instant now, long windowMillis) {
            return now.isBefore(firstSeenAt.plusMillis(windowMillis));
        }

        public long remainingMillis(Instant now, long windowMillis) {
            return Math.max(0, windowMillis - (now.toEpochMilli() - firstSeenAt.toEpochMilli()));
        }
    }

    /** 仅供审计/排障查看当前抑制键。 */
    public Map<String, SuppressionEntry> snapshot() {
        return Map.copyOf(entries);
    }

}
