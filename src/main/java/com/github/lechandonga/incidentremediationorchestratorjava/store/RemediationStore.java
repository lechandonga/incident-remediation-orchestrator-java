package com.github.lechandonga.incidentremediationorchestratorjava.store;

import com.github.lechandonga.incidentremediationorchestratorjava.model.Remediation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 处置流程内存仓库。 */
@Component
public class RemediationStore {
    private final ConcurrentHashMap<String, Remediation> byId = new ConcurrentHashMap<>();

    public void put(Remediation r) {
        byId.put(r.remediationId(), r);
    }

    public Optional<Remediation> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Remediation> all() {
        return List.copyOf(byId.values());
    }

    public void clear() {
        byId.clear();
    }
}
