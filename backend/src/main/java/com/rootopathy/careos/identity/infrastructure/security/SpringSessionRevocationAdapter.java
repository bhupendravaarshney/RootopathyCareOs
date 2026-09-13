package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.SessionRevocationPort;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

@Component
public final class SpringSessionRevocationAdapter implements SessionRevocationPort {
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public SpringSessionRevocationAdapter(
            FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void revokeAllForPrincipal(String principalName) {
        sessionRepository.findByPrincipalName(principalName).keySet().forEach(sessionRepository::deleteById);
    }
}
