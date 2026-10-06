package com.github.lechandonga.incidentremediationorchestratorjava.policy;

import com.github.lechandonga.incidentremediationorchestratorjava.model.RejectReason;

/** 策略判定结果。 */
public record PolicyDecision(boolean allowed, RejectReason rejectReason, String detail) {
    public static PolicyDecision allow() {
        return new PolicyDecision(true, null, "策略校验通过");
    }

    public static PolicyDecision deny(RejectReason reason, String detail) {
        return new PolicyDecision(false, reason, detail);
    }
}
