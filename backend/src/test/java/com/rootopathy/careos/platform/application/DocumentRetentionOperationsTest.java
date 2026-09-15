package com.rootopathy.careos.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionDirective;
import com.rootopathy.careos.platform.domain.DocumentRetentionEvidence;
import com.rootopathy.careos.platform.domain.DocumentRetentionPolicy;
import com.rootopathy.careos.platform.domain.DocumentRetentionReceipt;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentRetentionOperationsTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OBJECT_VERSION_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID DIRECTIVE_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final String STORAGE_VERSION_SHA256 = "b".repeat(64);
    private static final DocumentRetentionPolicy POLICY = new DocumentRetentionPolicy(
            "foundation.synthetic",
            Set.of("document.retention"),
            Duration.ofMinutes(1),
            Duration.ofDays(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(2));

    private DocumentRetentionPort retention;
    private DocumentEvidenceOperations evidence;
    private DefaultDocumentRetentionOperations operations;

    @BeforeEach
    void setUp() {
        retention = mock(DocumentRetentionPort.class);
        evidence = mock(DocumentEvidenceOperations.class);
        operations = new DefaultDocumentRetentionOperations(
                retention, evidence, POLICY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void appliesOnlyAfterPromotionAndRecordsMatchingEvidence() {
        var context = context(ORGANIZATION_ID, "retention-order");
        var document = document(ORGANIZATION_ID);
        var directive = directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(7)), true);
        when(evidence.findRetention(context, document, DIRECTIVE_ID)).thenReturn(Optional.empty());
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion(document)));
        when(evidence.findLatestRetention(context, document)).thenReturn(Optional.empty());
        when(retention.apply(any(), any())).thenAnswer(invocation ->
                receipt(invocation.getArgument(1, DocumentRetentionAuthorization.class)));
        when(evidence.recordRetention(any(), any(), any())).thenAnswer(invocation -> recorded(
                invocation.getArgument(1, DocumentRetentionAuthorization.class),
                invocation.getArgument(2, DocumentRetentionReceipt.class),
                context));

        var result = operations.apply(context, document, directive);

        assertThat(result.retentionDirectiveId()).isEqualTo(DIRECTIVE_ID);
        assertThat(result.retainUntil()).isEqualTo(directive.retainUntil());
        assertThat(result.legalHold()).isTrue();
        var order = inOrder(evidence, retention);
        order.verify(evidence).findRetention(context, document, DIRECTIVE_ID);
        order.verify(evidence).findPromotion(context, document);
        order.verify(evidence).findLatestRetention(context, document);
        order.verify(retention).apply(any(), any());
        order.verify(evidence).recordRetention(any(), any(), any());
    }

    @Test
    void exactReplayReturnsDurableEvidenceWithoutCallingStorage() {
        var context = context(ORGANIZATION_ID, "retention-replay");
        var document = document(ORGANIZATION_ID);
        var directive = directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(7)), false);
        var authorization = authorization(document, directive, null);
        var existing = recorded(authorization, receipt(authorization), context);
        when(evidence.findRetention(context, document, DIRECTIVE_ID))
                .thenReturn(Optional.of(existing));

        assertThat(operations.apply(context, document, directive)).isEqualTo(existing);
        verify(retention, never()).apply(any(), any());
        verify(evidence, never()).findPromotion(any(), any());
        verify(evidence, never()).recordRetention(any(), any(), any());

        var otherDocument = new DocumentObjectReference(
                ORGANIZATION_ID, UUID.randomUUID(), UUID.randomUUID());
        var otherAuthorization = authorization(otherDocument, directive, null);
        when(evidence.findRetention(context, document, DIRECTIVE_ID))
                .thenReturn(Optional.of(recorded(
                        otherAuthorization, receipt(otherAuthorization), context)));
        assertRetentionReason(
                () -> operations.apply(context, document, directive),
                "document-retention-evidence-conflict");
    }

    @Test
    void refusesMissingPromotionWithoutCallingStorage() {
        var context = context(ORGANIZATION_ID, "retention-no-promotion");
        var document = document(ORGANIZATION_ID);
        var directive = directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(7)), false);
        when(evidence.findRetention(context, document, DIRECTIVE_ID)).thenReturn(Optional.empty());
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());

        assertRetentionReason(
                () -> operations.apply(context, document, directive),
                "document-promotion-evidence-not-found");
        verify(retention, never()).apply(any(), any());
        verify(evidence, never()).recordRetention(any(), any(), any());
    }

    @Test
    void rejectsTenantPurposeAndPolicyBeforeStorage() {
        var document = document(ORGANIZATION_ID);
        var directive = directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(7)), false);
        assertRetentionReason(
                () -> operations.apply(
                        context(OTHER_ORGANIZATION_ID, "retention-cross-tenant"),
                        document,
                        directive),
                "document-retention-tenant-mismatch");
        assertRetentionReason(
                () -> operations.apply(
                        new AuthorizedTenantContext(
                                ORGANIZATION_ID,
                                ACTOR_ID,
                                "document.read",
                                "retention-purpose"),
                        document,
                        directive),
                "document-retention-purpose-not-approved");
        assertRetentionReason(
                () -> operations.apply(
                        context(ORGANIZATION_ID, "retention-policy"),
                        document,
                        new DocumentRetentionDirective(
                                DIRECTIVE_ID,
                                "different.policy",
                                NOW.plus(Duration.ofDays(7)),
                                false)),
                "document-retention-policy-mismatch");
        verify(retention, never()).apply(any(), any());
    }

    @Test
    void refusesShorteningHoldReleaseAndNoChange() {
        var context = context(ORGANIZATION_ID, "retention-monotonic");
        var document = document(ORGANIZATION_ID);
        var previousDirective = directive(UUID.randomUUID(), NOW.plus(Duration.ofDays(10)), true);
        var previousAuthorization = authorization(document, previousDirective, null);
        var previous = recorded(previousAuthorization, receipt(previousAuthorization), context);
        when(evidence.findRetention(context, document, DIRECTIVE_ID)).thenReturn(Optional.empty());
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion(document)));
        when(evidence.findLatestRetention(context, document)).thenReturn(Optional.of(previous));

        assertRetentionReason(
                () -> operations.apply(
                        context,
                        document,
                        directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(9)), true)),
                "document-retention-shortening-rejected");
        assertRetentionReason(
                () -> operations.apply(
                        context,
                        document,
                        directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(11)), false)),
                "document-legal-hold-release-not-supported");
        assertRetentionReason(
                () -> operations.apply(
                        context,
                        document,
                        directive(DIRECTIVE_ID, previous.retainUntil(), true)),
                "document-retention-no-change");

        var otherDocument = new DocumentObjectReference(
                ORGANIZATION_ID, UUID.randomUUID(), UUID.randomUUID());
        var otherDirective = directive(
                UUID.randomUUID(), NOW.plus(Duration.ofDays(10)), true);
        var otherAuthorization = authorization(otherDocument, otherDirective, null);
        when(evidence.findLatestRetention(context, document))
                .thenReturn(Optional.of(recorded(
                        otherAuthorization, receipt(otherAuthorization), context)));
        assertRetentionReason(
                () -> operations.apply(
                        context,
                        document,
                        directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(11)), true)),
                "document-retention-response-mismatch");
        verify(retention, never()).apply(any(), any());
    }

    @Test
    void storageFailureCannotCreateEvidence() {
        var context = context(ORGANIZATION_ID, "retention-storage-failure");
        var document = document(ORGANIZATION_ID);
        var directive = directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(7)), false);
        prepareNewDirective(context, document, directive);
        when(retention.apply(any(), any()))
                .thenThrow(new DocumentStorageException("document-retention-storage-unavailable"));

        assertThatThrownBy(() -> operations.apply(context, document, directive))
                .isInstanceOf(DocumentStorageException.class);
        verify(evidence, never()).recordRetention(any(), any(), any());
    }

    @Test
    void rejectsProviderOrEvidenceDrift() {
        var context = context(ORGANIZATION_ID, "retention-drift");
        var document = document(ORGANIZATION_ID);
        var directive = directive(DIRECTIVE_ID, NOW.plus(Duration.ofDays(7)), false);
        prepareNewDirective(context, document, directive);
        when(retention.apply(any(), any())).thenAnswer(invocation -> {
            var authorization = invocation.getArgument(1, DocumentRetentionAuthorization.class);
            return new DocumentRetentionReceipt(
                    authorization.document(),
                    authorization.retainUntil().plusSeconds(1),
                    authorization.legalHold(),
                    STORAGE_VERSION_SHA256);
        });
        assertRetentionReason(
                () -> operations.apply(context, document, directive),
                "document-retention-response-mismatch");
        verify(evidence, never()).recordRetention(any(), any(), any());

        org.mockito.Mockito.reset(retention);
        when(retention.apply(any(), any())).thenAnswer(invocation ->
                receipt(invocation.getArgument(1, DocumentRetentionAuthorization.class)));
        when(evidence.recordRetention(any(), any(), any())).thenAnswer(invocation -> {
            var authorization = invocation.getArgument(1, DocumentRetentionAuthorization.class);
            var providerReceipt = invocation.getArgument(2, DocumentRetentionReceipt.class);
            return new DocumentRetentionEvidence(
                    authorization.retentionDirectiveId(),
                    authorization.document(),
                    authorization.previousRetentionDirectiveId(),
                    authorization.policyKey(),
                    authorization.acceptedPurposes(),
                    authorization.minimumRetention(),
                    authorization.maximumRetention(),
                    authorization.maximumAuthorizationAge(),
                    authorization.maximumFutureSkew(),
                    authorization.retainUntil(),
                    authorization.legalHold(),
                    providerReceipt.storageVersionSha256(),
                    context.actorId(),
                    context.purpose(),
                    "different-correlation",
                    authorization.authorizedAt(),
                    NOW.plusMillis(1));
        });
        assertRetentionReason(
                () -> operations.apply(context, document, directive),
                "document-retention-evidence-conflict");
    }

    private void prepareNewDirective(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            DocumentRetentionDirective directive) {
        when(evidence.findRetention(context, document, directive.retentionDirectiveId()))
                .thenReturn(Optional.empty());
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion(document)));
        when(evidence.findLatestRetention(context, document)).thenReturn(Optional.empty());
    }

    private static DocumentRetentionAuthorization authorization(
            DocumentObjectReference document,
            DocumentRetentionDirective directive,
            UUID previousDirectiveId) {
        return new DocumentRetentionAuthorization(
                directive.retentionDirectiveId(),
                promotion(document),
                previousDirectiveId,
                POLICY.policyKey(),
                POLICY.acceptedPurposes(),
                POLICY.minimumRetention(),
                POLICY.maximumRetention(),
                POLICY.maximumAuthorizationAge(),
                POLICY.maximumFutureSkew(),
                directive.retainUntil(),
                directive.legalHold(),
                "document.retention",
                NOW);
    }

    private static DocumentRetentionReceipt receipt(DocumentRetentionAuthorization authorization) {
        return new DocumentRetentionReceipt(
                authorization.document(),
                authorization.retainUntil(),
                authorization.legalHold(),
                STORAGE_VERSION_SHA256);
    }

    private static DocumentRetentionEvidence recorded(
            DocumentRetentionAuthorization authorization,
            DocumentRetentionReceipt receipt,
            AuthorizedTenantContext context) {
        return new DocumentRetentionEvidence(
                authorization.retentionDirectiveId(),
                authorization.document(),
                authorization.previousRetentionDirectiveId(),
                authorization.policyKey(),
                authorization.acceptedPurposes(),
                authorization.minimumRetention(),
                authorization.maximumRetention(),
                authorization.maximumAuthorizationAge(),
                authorization.maximumFutureSkew(),
                authorization.retainUntil(),
                authorization.legalHold(),
                receipt.storageVersionSha256(),
                context.actorId(),
                context.purpose(),
                context.correlationId(),
                authorization.authorizedAt(),
                NOW.plusMillis(1));
    }

    private static DocumentRetentionDirective directive(
            UUID directiveId, Instant retainUntil, boolean legalHold) {
        return new DocumentRetentionDirective(
                directiveId, POLICY.policyKey(), retainUntil, legalHold);
    }

    private static DocumentPromotionEvidence promotion(DocumentObjectReference document) {
        var scannedAt = NOW.minusSeconds(20);
        var result = new MalwareScanResult(
                document,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.1",
                "a".repeat(64),
                scannedAt);
        return new DocumentPromotionEvidence(
                new DocumentScanAttestation(UUID.randomUUID(), result, scannedAt.plusMillis(1)),
                "foundation.synthetic",
                Set.of("clamav"),
                Duration.ofMinutes(5),
                Duration.ofSeconds(2),
                NOW.minusSeconds(10));
    }

    private static DocumentObjectReference document(UUID organizationId) {
        return new DocumentObjectReference(organizationId, DOCUMENT_ID, OBJECT_VERSION_ID);
    }

    private static AuthorizedTenantContext context(UUID organizationId, String correlationId) {
        return new AuthorizedTenantContext(
                organizationId, ACTOR_ID, "document.retention", correlationId);
    }

    private static void assertRetentionReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation, String reasonCode) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        DocumentRetentionException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reasonCode));
    }
}
