package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.io.InputStream;
import java.util.Objects;

public final class DefaultDocumentSecurityOperations implements DocumentSecurityOperations {
    private static final String EVIDENCE_MISSING = "document-quarantine-evidence-not-found";
    private static final String EVIDENCE_CONFLICT = "document-quarantine-evidence-conflict";
    private static final String REFERENCE_MISMATCH = "document-evidence-reference-mismatch";
    private static final String DIGEST_MISMATCH = "document-scan-evidence-digest-mismatch";
    private static final String SCAN_RESULT_INVALID = "document-scan-result-invalid";

    private final PrivateDocumentStoragePort storage;
    private final MalwareScannerPort scanner;
    private final DocumentEvidenceOperations evidence;

    public DefaultDocumentSecurityOperations(
            PrivateDocumentStoragePort storage,
            MalwareScannerPort scanner,
            DocumentEvidenceOperations evidence) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public DocumentQuarantineEvidence quarantine(
            AuthorizedTenantContext context,
            DocumentQuarantineRequest request,
            InputStream content) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        var expected = new DocumentObjectReference(
                context.organizationId(), request.documentId(), request.objectVersionId());

        evidence.findQuarantine(context, expected).ifPresent(existing -> {
            if (!matches(existing, request)) {
                throw new DocumentEvidenceException(EVIDENCE_CONFLICT);
            }
        });
        var stored = storage.quarantine(context, request, content);
        if (!expected.equals(stored)) {
            throw new DocumentEvidenceException(REFERENCE_MISMATCH);
        }
        return evidence.recordQuarantine(context, request, stored);
    }

    @Override
    public DocumentScanAttestation scan(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        var quarantine = evidence.findQuarantine(context, document)
                .orElseThrow(() -> new DocumentEvidenceException(EVIDENCE_MISSING));
        var result = scanner.scan(context, document);
        if (result == null) {
            throw new DocumentEvidenceException(SCAN_RESULT_INVALID);
        }
        if (!document.equals(result.document())) {
            throw new DocumentEvidenceException(REFERENCE_MISMATCH);
        }
        if (result.verdict() != MalwareScanVerdict.ERROR
                && !quarantine.sha256().equals(result.sha256())) {
            throw new DocumentEvidenceException(DIGEST_MISMATCH);
        }
        return evidence.recordScan(context, result);
    }

    private static boolean matches(
            DocumentQuarantineEvidence evidence, DocumentQuarantineRequest request) {
        return evidence.document().documentId().equals(request.documentId())
                && evidence.document().objectVersionId().equals(request.objectVersionId())
                && evidence.declaredBytes() == request.declaredBytes()
                && evidence.mediaType().equals(request.mediaType())
                && evidence.sha256().equals(request.sha256());
    }
}
