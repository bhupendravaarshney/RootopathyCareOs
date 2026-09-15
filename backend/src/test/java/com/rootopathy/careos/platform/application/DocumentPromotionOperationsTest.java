package com.rootopathy.careos.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
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

class DocumentPromotionOperationsTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OBJECT_VERSION_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final String SHA_256 = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    private final AuthorizedTenantContext context = new AuthorizedTenantContext(
            ORGANIZATION_ID, ACTOR_ID, "document.promote", "promotion-test-1");
    private final DocumentObjectReference document =
            new DocumentObjectReference(ORGANIZATION_ID, DOCUMENT_ID, OBJECT_VERSION_ID);
    private final DocumentPromotionPolicy policy = new DocumentPromotionPolicy(
            "foundation.synthetic", Set.of("clamav"), Duration.ofMinutes(5), Duration.ofSeconds(2));
    private final DocumentQuarantineEvidence quarantine = new DocumentQuarantineEvidence(
            document, 128, "application/pdf", SHA_256, NOW.minusSeconds(20));

    private DocumentPromotionPort promotion;
    private DocumentEvidenceOperations evidence;
    private DocumentPromotionOperations operations;

    @BeforeEach
    void setUp() {
        promotion = mock(DocumentPromotionPort.class);
        evidence = mock(DocumentEvidenceOperations.class);
        operations = new DefaultDocumentPromotionOperations(
                promotion, evidence, policy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsExistingDurablePromotionWithoutRepeatingStorageIo() {
        var existing = promotionEvidence(scan(MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.minusSeconds(30)));
        when(evidence.findPromotion(context, document)).thenReturn(Optional.of(existing));

        assertThat(operations.promote(context, document)).isSameAs(existing);

        verify(evidence).findPromotion(context, document);
        verifyNoInteractions(promotion);
        verify(evidence, never()).findQuarantine(context, document);
    }

    @Test
    void rejectsMissingQuarantineBeforeLookingForAScanOrCallingStorage() {
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());
        when(evidence.findQuarantine(context, document)).thenReturn(Optional.empty());

        assertReason(() -> operations.promote(context, document),
                "document-quarantine-evidence-not-found");

        verify(evidence, never()).findLatestScan(context, document);
        verifyNoInteractions(promotion);
    }

    @Test
    void rejectsNonCleanAndDigestMismatchedEvidence() {
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());
        when(evidence.findQuarantine(context, document)).thenReturn(Optional.of(quarantine));

        var infected = scan(MalwareScanVerdict.INFECTED, "clamav", SHA_256, NOW.minusSeconds(10));
        when(evidence.findLatestScan(context, document)).thenReturn(Optional.of(infected));
        assertReason(() -> operations.promote(context, document),
                "document-promotion-clean-scan-required");

        var mismatched = scan(MalwareScanVerdict.CLEAN, "clamav", "b".repeat(64), NOW.minusSeconds(10));
        when(evidence.findLatestScan(context, document)).thenReturn(Optional.of(mismatched));
        assertReason(() -> operations.promote(context, document),
                "document-promotion-digest-mismatch");
        verifyNoInteractions(promotion);
    }

    @Test
    void rejectsUnapprovedStaleAndFutureScannerEvidence() {
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());
        when(evidence.findQuarantine(context, document)).thenReturn(Optional.of(quarantine));

        when(evidence.findLatestScan(context, document))
                .thenReturn(Optional.of(scan(
                        MalwareScanVerdict.CLEAN, "unapproved", SHA_256, NOW.minusSeconds(10))));
        assertReason(() -> operations.promote(context, document),
                "document-promotion-scanner-not-approved");

        when(evidence.findLatestScan(context, document))
                .thenReturn(Optional.of(scan(
                        MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.minusSeconds(301))));
        assertReason(() -> operations.promote(context, document),
                "document-promotion-scan-stale");

        when(evidence.findLatestScan(context, document))
                .thenReturn(Optional.of(scan(
                        MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.plusSeconds(3))));
        assertReason(() -> operations.promote(context, document),
                "document-promotion-scan-in-future");
        verifyNoInteractions(promotion);
    }

    @Test
    void promotesTheExactObjectBeforeRecordingItsPolicyEvidence() {
        var scan = scan(MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.minusSeconds(10));
        var authorization = promotionAuthorization(scan);
        var recorded = promotionEvidence(scan);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());
        when(evidence.findQuarantine(context, document)).thenReturn(Optional.of(quarantine));
        when(evidence.findLatestScan(context, document)).thenReturn(Optional.of(scan));
        when(evidence.recordPromotion(context, scan, policy)).thenReturn(recorded);
        when(promotion.promote(context, authorization)).thenReturn(document);

        assertThat(operations.promote(context, document)).isSameAs(recorded);

        var order = inOrder(evidence, promotion);
        order.verify(evidence).findPromotion(context, document);
        order.verify(evidence).findQuarantine(context, document);
        order.verify(evidence).findLatestScan(context, document);
        order.verify(promotion).promote(context, authorization);
        order.verify(evidence).recordPromotion(context, scan, policy);
    }

    @Test
    void rejectsAStorageAdapterThatReturnsAnotherObjectReference() {
        var scan = scan(MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.minusSeconds(10));
        var authorization = promotionAuthorization(scan);
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());
        when(evidence.findQuarantine(context, document)).thenReturn(Optional.of(quarantine));
        when(evidence.findLatestScan(context, document)).thenReturn(Optional.of(scan));
        when(promotion.promote(context, authorization))
                .thenReturn(new DocumentObjectReference(
                        ORGANIZATION_ID, UUID.randomUUID(), OBJECT_VERSION_ID));

        assertReason(() -> operations.promote(context, document),
                "document-promotion-reference-mismatch");
        verify(evidence, never()).recordPromotion(context, scan, policy);
    }

    @Test
    void rejectsEvidenceStoreDriftAfterThePrivateStorageCopy() {
        var scan = scan(MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.minusSeconds(10));
        var differentScan = scan(
                MalwareScanVerdict.CLEAN, "clamav", SHA_256, NOW.minusSeconds(9));
        when(evidence.findPromotion(context, document)).thenReturn(Optional.empty());
        when(evidence.findQuarantine(context, document)).thenReturn(Optional.of(quarantine));
        when(evidence.findLatestScan(context, document)).thenReturn(Optional.of(scan));
        when(promotion.promote(context, promotionAuthorization(scan))).thenReturn(document);
        when(evidence.recordPromotion(context, scan, policy))
                .thenReturn(promotionEvidence(differentScan));

        assertReason(() -> operations.promote(context, document),
                "document-promotion-evidence-conflict");
        verify(promotion).promote(context, promotionAuthorization(scan));
    }

    private DocumentScanAttestation scan(
            MalwareScanVerdict verdict, String scannerKey, String sha256, Instant scannedAt) {
        return new DocumentScanAttestation(
                UUID.randomUUID(),
                new MalwareScanResult(
                        document, verdict, scannerKey, "20260915.1", sha256, scannedAt),
                scannedAt.plusMillis(1));
    }

    private DocumentPromotionEvidence promotionEvidence(DocumentScanAttestation scan) {
        return new DocumentPromotionEvidence(
                scan,
                policy.policyKey(),
                policy.acceptedScannerKeys(),
                policy.maximumScanAge(),
                policy.maximumFutureSkew(),
                NOW);
    }

    private DocumentPromotionAuthorization promotionAuthorization(DocumentScanAttestation scan) {
        return new DocumentPromotionAuthorization(
                scan,
                policy.policyKey(),
                policy.acceptedScannerKeys(),
                policy.maximumScanAge(),
                policy.maximumFutureSkew(),
                NOW);
    }

    private static void assertReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation, String reasonCode) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        DocumentPromotionException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reasonCode));
    }
}
