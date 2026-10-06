package com.github.lechandonga.incidentremediationorchestratorjava.audit;

import com.github.lechandonga.incidentremediationorchestratorjava.model.RemediationStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审计重放器：仅依赖审计事件序列重建处置当前状态。
 * 终态事件优先级最高；没有终态时按最后的生命周期事件推导，
 * 从而验证“仅凭审计记录就能重建任一处置的当前状态”。
 */
@Component
public class AuditReplayer {

    public RemediationStatus replayStatus(String remediationId, List<AuditEvent> events) {
        RemediationStatus derived = RemediationStatus.PENDING;
        RemediationStatus terminal = null;
        for (AuditEvent e : events) {
            if (!remediationId.equals(e.remediationId())) {
                continue;
            }
            switch (e.type()) {
                case REMEDIATION_CREATED -> derived = RemediationStatus.PENDING;
                case REMEDIATION_STARTED -> derived = RemediationStatus.RUNNING;
                case REMEDIATION_SUCCEEDED -> terminal = RemediationStatus.SUCCEEDED;
                case REMEDIATION_FAILED -> terminal = RemediationStatus.FAILED;
                case REMEDIATION_CANCELLED -> terminal = RemediationStatus.CANCELLED;
                case REMEDIATION_TIMEOUT -> terminal = RemediationStatus.TIMED_OUT;
                default -> { }
            }
        }
        return terminal != null ? terminal : derived;
    }
}
