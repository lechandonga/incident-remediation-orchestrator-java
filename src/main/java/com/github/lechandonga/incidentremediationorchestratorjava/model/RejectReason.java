package com.github.lechandonga.incidentremediationorchestratorjava.model;

/**
 * 策略/权限拒绝的细分原因，保证“越权”与“不在允许范围内”可区分。
 */
public enum RejectReason {
    /** 告警来源未在策略中心登记。 */
    UNKNOWN_SOURCE,
    /** 触发者角色未知。 */
    UNKNOWN_ROLE,
    /** 该来源不允许触发此动作（动作不在来源允许范围内）。 */
    SOURCE_ACTION_NOT_ALLOWED,
    /** 该角色无权触发此动作（越权）。 */
    ROLE_ACTION_FORBIDDEN
}
