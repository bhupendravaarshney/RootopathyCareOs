package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.identity.domain.AuthenticationEventType;
import com.rootopathy.careos.identity.domain.CredentialAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationFailureRecorder {
    private final IdentityStore identityStore;
    private final SecurityTokenPort tokenCodec;

    public AuthenticationFailureRecorder(IdentityStore identityStore, SecurityTokenPort tokenCodec) {
        this.identityStore = identityStore;
        this.tokenCodec = tokenCodec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            CredentialAccount account,
            AuthenticationEventType eventType,
            String correlationId,
            String remoteAddress) {
        identityStore.recordAuthenticationEvent(
                account.id(),
                eventType,
                tokenCodec.digest(account.email()),
                tokenCodec.digest(remoteAddress == null || remoteAddress.isBlank() ? "unavailable" : remoteAddress),
                correlationId);
    }
}
