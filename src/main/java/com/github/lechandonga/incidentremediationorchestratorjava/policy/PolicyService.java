package com.github.lechandonga.incidentremediationorchestratorjava.policy;

import com.github.lechandonga.incidentremediationorchestratorjava.model.ActionType;
import com.github.lechandonga.incidentremediationorchestratorjava.model.RejectReason;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 策略中心：先判定“来源可触发的动作范围”，再判定“角色权限”。
 * 两类拒绝给出不同的 {@link RejectReason}，且未登记来源/未知角色也分别区分。
 *
 * <ul>
 *   <li>prometheus：重启服务、杀进程、清缓存、扩容、通知</li>
 *   <li>cloudwatch：扩容、隔离主机、通知</li>
 *   <li>zabbix：重启服务、清缓存、通知</li>
 * </ul>
 * 角色：admin 全部动作；operator 除 ISOLATE_HOST 外；viewer 只读（仅可触发通知）。
 */
@Service
public class PolicyService {

    private final Map<String, Set<ActionType>> sourceActions = Map.of(
            "prometheus", EnumSet.of(ActionType.RESTART_SERVICE, ActionType.KILL_PROCESS,
                    ActionType.CLEAR_CACHE, ActionType.SCALE_OUT, ActionType.NOTIFY_ONCALL),
            "cloudwatch", EnumSet.of(ActionType.SCALE_OUT, ActionType.ISOLATE_HOST, ActionType.NOTIFY_ONCALL),
            "zabbix", EnumSet.of(ActionType.RESTART_SERVICE, ActionType.CLEAR_CACHE, ActionType.NOTIFY_ONCALL)
    );

    private final Map<String, Set<ActionType>> roleActions = Map.of(
            "admin", EnumSet.allOf(ActionType.class),
            "operator", EnumSet.of(ActionType.RESTART_SERVICE, ActionType.KILL_PROCESS,
                    ActionType.CLEAR_CACHE, ActionType.SCALE_OUT, ActionType.NOTIFY_ONCALL),
            "viewer", EnumSet.of(ActionType.NOTIFY_ONCALL)
    );

    public PolicyDecision evaluate(String source, String role, ActionType action) {
        Set<ActionType> allowedBySource = sourceActions.get(source);
        if (allowedBySource == null) {
            return PolicyDecision.deny(RejectReason.UNKNOWN_SOURCE,
                    "未知告警来源: " + source + "，未在策略中心登记");
        }
        Set<ActionType> allowedByRole = roleActions.get(role);
        if (allowedByRole == null) {
            return PolicyDecision.deny(RejectReason.UNKNOWN_ROLE,
                    "未知角色: " + role);
        }
        if (!allowedBySource.contains(action)) {
            return PolicyDecision.deny(RejectReason.SOURCE_ACTION_NOT_ALLOWED,
                    "来源 " + source + " 不允许触发动作 " + action
                            + "，允许范围: " + allowedBySource);
        }
        if (!allowedByRole.contains(action)) {
            return PolicyDecision.deny(RejectReason.ROLE_ACTION_FORBIDDEN,
                    "角色 " + role + " 无权执行动作 " + action + "（越权），允许范围: " + allowedByRole);
        }
        return PolicyDecision.allow();
    }

    public Set<String> knownSources() {
        return sourceActions.keySet();
    }

    public Set<String> knownRoles() {
        return roleActions.keySet();
    }
}
