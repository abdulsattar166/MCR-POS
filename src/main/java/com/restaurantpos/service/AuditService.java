package com.restaurantpos.service;

import com.restaurantpos.dao.SystemRepository;
import com.restaurantpos.security.Session;

public final class AuditService {
    private final SystemRepository repo;
    private final Session session;

    public AuditService(SystemRepository repo, Session session) {
        this.repo = repo;
        this.session = session;
    }

    public void log(String action, String entity, Long entityId, String details) {
        Long userId = session.active() ? session.userId() : null;
        String username = session.active() ? session.user().username() : "system";
        repo.audit(userId, username, action, entity, entityId, details);
    }

    public void notify(String type, String title, String message, String severity) {
        repo.notify(type, title, message, severity);
    }
}
