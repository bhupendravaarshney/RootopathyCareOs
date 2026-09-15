package com.rootopathy.careos.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessGrantEvidence;
import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentAccessOperationsTest {
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
    private static final UUID ACCESS_GRANT_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final String SHA_256 = "a".repeat(64);
    private static final DocumentAccessPolicy POLICY = new DocumentAccessPolicy(
            "foundation.synthetic",
            Set.of("document.read"),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(2));

    private SignedDocumentAccessPort access;
    private DocumentEvidenceOperations evidence;
    private DefaultDocumentAccessOperations operations;

    @BeforeEach
    void setUp() {
        access = mock(SignedDocumentAccessPort.class);
        evidence = mock(DocumentEvidenceOperations.class);
        operations = new DefaultDocumentAccessOperations(
                access,
                evidence,
                POLICY,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ACCESS_GRANT_ID);
    }

    @Test
    void signsOnlyAfterCommittedPromotionAndRecordsEvidenceBeforeReturning() {
        var context = context(ORGANIZATION_ID, "access-order");
        var document = document(ORGANIZATION_ID);
        var promotion = promotion(document);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion));
        when(access.createReadAccess(any(), any())).thenAnswer(invocation -> {
            var authorization = invocation.getArgument(1, DocumentAccessAuthorization.class);
            return signed(authorization);
        });
        when(evidence.recordAccessGrant(any(), any())).thenAnswer(invocation ->
                recorded(invocation.getArgument(1, DocumentAccessAuthorization.class), context));

        var result = operations.createReadAccess(context, document, Duration.ofMinutes(5));

        assertThat(result.accessGrantId()).isEqualTo(ACCESS_GRANT_ID);
        assertThat(result.document()).isEqualTo(document);
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(result.readUrl().getRawQuery()).contains("signature=synthetic");
        var order = inOrder(evidence, access);
        order.verify(evidence).findPromotion(context, document);
        order.verify(access).createReadAccess(any(), any());
        order.verify(evidence).recordAccessGrant(any(), any());
    }

    @Test
    void refusesMissingPromotionEvidenceWithoutCallingTheSigner() {
        var context = context(ORGANIZATION_ID, "access-no-promotion");
        var document = document(ORGANIZATION_ID);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());

        assertAccessReason(
                () -> operations.createReadAccess(context, document, Duration.ofMinutes(5)),
                "document-promotion-evidence-not-found");
        verify(access, never()).createReadAccess(any(), any());
        verify(evidence, never()).recordAccessGrant(any(), any());
    }

    @Test
    void rejectsCrossTenantAndUnapprovedPurposeBeforeSigning() {
        var document = document(ORGANIZATION_ID);
        assertAccessReason(
                () -> operations.createReadAccess(
                        context(OTHER_ORGANIZATION_ID, "access-cross-tenant"),
                        document,
                        Duration.ofMinutes(5)),
                "document-access-tenant-mismatch");
        assertAccessReason(
                () -> operations.createReadAccess(
                        new AuthorizedTenantContext(
                                ORGANIZATION_ID, ACTOR_ID, "document.export", "access-purpose"),
                        document,
                        Duration.ofMinutes(5)),
                "document-access-purpose-not-approved");
        verify(access, never()).createReadAccess(any(), any());
        verify(evidence, never()).findPromotion(any(), any());
    }

    @Test
    void signerFailureCannotCreateAccessEvidence() {
        var context = context(ORGANIZATION_ID, "access-signer-failure");
        var document = document(ORGANIZATION_ID);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion(document)));
        when(access.createReadAccess(any(), any()))
                .thenThrow(new DocumentStorageException("document-access-storage-unavailable"));

        assertThatThrownBy(() ->
                        operations.createReadAccess(context, document, Duration.ofMinutes(5)))
                .isInstanceOf(DocumentStorageException.class);
        verify(evidence, never()).recordAccessGrant(any(), any());
    }

    @Test
    void rejectsAChangedSignerResponseBeforeRecordingEvidence() {
        var context = context(ORGANIZATION_ID, "access-signer-drift");
        var document = document(ORGANIZATION_ID);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion(document)));
        when(access.createReadAccess(any(), any())).thenAnswer(invocation -> {
            var authorization = invocation.getArgument(1, DocumentAccessAuthorization.class);
            return new SignedDocumentAccess(
                    UUID.randomUUID(),
                    authorization.document(),
                    URI.create("https://objects.example/clean?signature=synthetic"),
                    authorization.expiresAt());
        });

        assertAccessReason(
                () -> operations.createReadAccess(context, document, Duration.ofMinutes(5)),
                "document-access-response-mismatch");
        verify(evidence, never()).recordAccessGrant(any(), any());
    }

    @Test
    void rejectsChangedDurableEvidenceInsteadOfReturningTheBearerUrl() {
        var context = context(ORGANIZATION_ID, "access-evidence-drift");
        var document = document(ORGANIZATION_ID);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(promotion(document)));
        when(access.createReadAccess(any(), any())).thenAnswer(invocation ->
                signed(invocation.getArgument(1, DocumentAccessAuthorization.class)));
        when(evidence.recordAccessGrant(any(), any())).thenAnswer(invocation -> {
            var authorization = invocation.getArgument(1, DocumentAccessAuthorization.class);
            return new DocumentAccessGrantEvidence(
                    authorization.accessGrantId(),
                    authorization.promotionEvidence(),
                    authorization.policyKey(),
                    authorization.acceptedPurposes(),
                    authorization.requestedTtl(),
                    authorization.maximumTtl(),
                    authorization.maximumAuthorizationAge(),
                    authorization.maximumFutureSkew(),
                    context.actorId(),
                    authorization.purpose(),
                    "different-correlation",
                    authorization.authorizedAt(),
                    NOW.plusMillis(1),
                    authorization.expiresAt());
        });

        assertAccessReason(
                () -> operations.createReadAccess(context, document, Duration.ofMinutes(5)),
                "document-access-evidence-conflict");
    }

    private static SignedDocumentAccess signed(DocumentAccessAuthorization authorization) {
        return new SignedDocumentAccess(
                authorization.accessGrantId(),
                authorization.document(),
                URI.create("https://objects.example/clean?signature=synthetic"),
                authorization.expiresAt());
    }

    private static DocumentAccessGrantEvidence recorded(
            DocumentAccessAuthorization authorization, AuthorizedTenantContext context) {
        return new DocumentAccessGrantEvidence(
                authorization.accessGrantId(),
                authorization.promotionEvidence(),
                authorization.policyKey(),
                authorization.acceptedPurposes(),
                authorization.requestedTtl(),
                authorization.maximumTtl(),
                authorization.maximumAuthorizationAge(),
                authorization.maximumFutureSkew(),
                context.actorId(),
                context.purpose(),
                context.correlationId(),
                authorization.authorizedAt(),
                NOW.plusMillis(1),
                authorization.expiresAt());
    }

    private static DocumentPromotionEvidence promotion(DocumentObjectReference document) {
        var scannedAt = NOW.minusSeconds(20);
        var result = new MalwareScanResult(
                document,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.1",
                SHA_256,
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
                organizationId, ACTOR_ID, "document.read", correlationId);
    }

    private static void assertAccessReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation, String reasonCode) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        DocumentAccessException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reasonCode));
    }
}
