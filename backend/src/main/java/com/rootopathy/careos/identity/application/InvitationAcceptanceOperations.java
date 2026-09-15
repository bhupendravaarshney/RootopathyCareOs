package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.identity.domain.InvitationAcceptance;
import java.util.UUID;
import java.util.function.Function;

public interface InvitationAcceptanceOperations {
    InvitationAcceptance accept(
            AcceptanceCommand command,
            Function<InvitationAcceptance, GovernanceEvidence> evidenceFactory);

    record AcceptanceCommand(
            String tokenHash,
            UUID authenticatedUserId,
            String authenticatedEmail,
            String passwordHash,
            String correlationId) {}
}
