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
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentSecurityOperationsTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OBJECT_VERSION_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final String SHA_256 = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-15T06:00:00Z");

    private final AuthorizedTenantContext context = new AuthorizedTenantContext(
            ORGANIZATION_ID, ACTOR_ID, "document.security", "document-test-1");
    private final DocumentObjectReference reference =
            new DocumentObjectReference(ORGANIZATION_ID, DOCUMENT_ID, OBJECT_VERSION_ID);
    private final DocumentQuarantineRequest request =
            new DocumentQuarantineRequest(DOCUMENT_ID, OBJECT_VERSION_ID, 128, "application/pdf", SHA_256);
    private final DocumentQuarantineEvidence quarantine =
            new DocumentQuarantineEvidence(reference, 128, "application/pdf", SHA_256, NOW);

    private PrivateDocumentStoragePort storage;
    private MalwareScannerPort scanner;
    private DocumentEvidenceOperations evidence;
    private DocumentSecurityOperations operations;

    @BeforeEach
    void setUp() {
        storage = mock(PrivateDocumentStoragePort.class);
        scanner = mock(MalwareScannerPort.class);
        evidence = mock(DocumentEvidenceOperations.class);
        operations = new DefaultDocumentSecurityOperations(storage, scanner, evidence);
    }

    @Test
    void checksTheAuthorizedEvidenceBoundaryBeforeStartingUploadIo() {
        when(evidence.findQuarantine(context, reference))
                .thenThrow(new IllegalStateException("authorized transaction required"));

        assertThatThrownBy(() -> operations.quarantine(context, request, InputStream.nullInputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorized transaction");

        verifyNoInteractions(storage);
    }

    @Test
    void persistsExactEvidenceOnlyAfterTheStorageAdapterAcceptsTheObject() {
        var content = InputStream.nullInputStream();
        when(evidence.findQuarantine(context, reference)).thenReturn(Optional.empty());
        when(storage.quarantine(context, request, content)).thenReturn(reference);
        when(evidence.recordQuarantine(context, request, reference)).thenReturn(quarantine);

        assertThat(operations.quarantine(context, request, content)).isSameAs(quarantine);

        var order = inOrder(evidence, storage);
        order.verify(evidence).findQuarantine(context, reference);
        order.verify(storage).quarantine(context, request, content);
        order.verify(evidence).recordQuarantine(context, request, reference);
    }

    @Test
    void rejectsAConflictingReplayBeforeCallingStorage() {
        var conflicting = new DocumentQuarantineEvidence(
                reference, 129, "application/pdf", SHA_256, NOW);
        when(evidence.findQuarantine(context, reference)).thenReturn(Optional.of(conflicting));

        assertReason(
                () -> operations.quarantine(context, request, InputStream.nullInputStream()),
                "document-quarantine-evidence-conflict");

        verifyNoInteractions(storage);
        verify(evidence, never()).recordQuarantine(context, request, reference);
    }

    @Test
    void rejectsAContentVerifiedVerdictWhoseDigestDoesNotMatchQuarantine() {
        var result = new MalwareScanResult(
                reference,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.1",
                "b".repeat(64),
                NOW);
        when(evidence.findQuarantine(context, reference)).thenReturn(Optional.of(quarantine));
        when(scanner.scan(context, reference)).thenReturn(result);

        assertReason(
                () -> operations.scan(context, reference),
                "document-scan-evidence-digest-mismatch");

        verify(evidence, never()).recordScan(context, result);
    }

    @Test
    void durablyRecordsScannerErrorsEvenWhenTheDigestCouldNotBeObserved() {
        var result = new MalwareScanResult(
                reference,
                MalwareScanVerdict.ERROR,
                "clamav",
                "unknown",
                "0".repeat(64),
                NOW);
        var attestation = new DocumentScanAttestation(UUID.randomUUID(), result, NOW.plusMillis(1));
        when(evidence.findQuarantine(context, reference)).thenReturn(Optional.of(quarantine));
        when(scanner.scan(context, reference)).thenReturn(result);
        when(evidence.recordScan(context, result)).thenReturn(attestation);

        assertThat(operations.scan(context, reference)).isSameAs(attestation);

        var order = inOrder(evidence, scanner);
        order.verify(evidence).findQuarantine(context, reference);
        order.verify(scanner).scan(context, reference);
        order.verify(evidence).recordScan(context, result);
    }

    private static void assertReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation, String reasonCode) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        DocumentEvidenceException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reasonCode));
    }
}
