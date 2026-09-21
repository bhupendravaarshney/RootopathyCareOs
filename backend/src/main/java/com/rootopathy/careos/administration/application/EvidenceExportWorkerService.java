package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.domain.*;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenceExportWorkerService {
  private static final int SUMMARY_ROWS = 100_000;
  private static final int DETAIL_ROWS = 25_000;
  private static final long SUMMARY_BYTES = 250L * 1024 * 1024;
  private static final long DETAIL_BYTES = 100L * 1024 * 1024;
  private final ServiceIdentityAuthorizationOperations authorization;
  private final EvidenceExportStore store;
  private final EvidenceExportArtifactStore artifacts;
  private final GovernanceEvidenceOperations evidence;
  private final ConsumerInboxOperations inbox;
  private final ObjectMapper mapper;

  public EvidenceExportWorkerService(
      ServiceIdentityAuthorizationOperations authorization,
      EvidenceExportStore store,
      EvidenceExportArtifactStore artifacts,
      GovernanceEvidenceOperations evidence,
      ConsumerInboxOperations inbox,
      ObjectMapper mapper) {
    this.authorization = authorization;
    this.store = store;
    this.artifacts = artifacts;
    this.evidence = evidence;
    this.inbox = inbox;
    this.mapper = mapper;
  }

  public Result consume(OutboxEnvelope envelope, String workerId, String presentedCredential) {
    if (!"evidence.export.authorized".equals(envelope.eventName()) || envelope.schemaVersion() != 1
        || !"evidence_export".equals(envelope.aggregateType())) {
      throw new IllegalArgumentException("unsupported export-worker event");
    }
    var command = new Command(envelope.organizationId(), envelope.aggregateId(), workerId,
        presentedCredential, envelope.correlationId());
    return authorization.execute(request(command, "evidence.export.generate"), context -> {
      var result = new Result[1];
      inbox.execute(context, InboundOutboxEvent.from("m1-export-worker-v1", envelope),
          () -> result[0] = generate(context, command));
      return result[0] == null
          ? new Result(command.exportId(), "duplicate", null, null, null)
          : result[0];
    });
  }

  public Result generate(Command command) {
    return authorization.execute(request(command, "evidence.export.generate"),
        context -> generate(context, command));
  }

  private Result generate(AuthorizedTenantContext context, Command command) {
      var work = store.claim(context, command.exportId(), command.workerId());
      EvidenceExportArtifactStore.StoredArtifact stored = null;
      var readyPersisted = false;
      try {
        var detail = work.projection().contains("-detail-");
        var maximumRows = detail ? DETAIL_ROWS : SUMMARY_ROWS;
        var maximumBytes = detail ? DETAIL_BYTES : SUMMARY_BYTES;
        var rows = store.rows(context, work, maximumRows);
        if (rows.size() > maximumRows) {
          return failed(context, work, "evidence.export.row_limit", false);
        }
        var artifact = serialize(work, rows, maximumBytes);
        stored = artifacts.store(
            context,
            work.exportId(),
            artifact.contentType(),
            artifact.filename(),
            artifact.bytes(),
            artifact.digest());
        var ready = store.ready(
            context,
            work,
            stored.opaqueReference(),
            stored.sha256(),
            artifact.contentType(),
            artifact.filename(),
            rows.size(),
            stored.byteCount());
        readyPersisted = true;
        var payload = json(Map.of(
            "artifactDigest", ready.artifactDigest(),
            "exportId", ready.exportId(),
            "expiryTime", ready.expiresAt(),
            "rowCount", ready.rowCount(),
            "state", "ready"));
        evidence.record(context, GovernanceEvidence.auditOnly(new AuditRecord(
            "evidence.export.completed", 1, "evidence_export", ready.exportId(), null, payload)));
        return new Result(ready.exportId(), "ready", ready.rowCount(), ready.byteCount(), null);
      } catch (ExportLimitException exception) {
        return failed(context, work, exception.code, false);
      } catch (RuntimeException exception) {
        if (stored != null && !deleteAfterFailure(context, work, stored, exception)) {
          throw exception;
        }
        // Once the ready transition has occurred, its audit evidence must commit in that same
        // transaction.  Re-throwing rolls the database work back after the external artifact has
        // been removed; attempting a running->failed transition here would use a stale revision.
        if (readyPersisted) throw exception;
        return failed(context, work, "evidence.export.generation_failed", true);
      }
  }

  private boolean deleteAfterFailure(
      AuthorizedTenantContext context,
      EvidenceExportStore.Work work,
      EvidenceExportArtifactStore.StoredArtifact stored,
      RuntimeException failure) {
    try {
      artifacts.delete(context, work.exportId(), stored.opaqueReference(), stored.sha256());
      return true;
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
      return false;
    }
  }

  private Result failed(
      AuthorizedTenantContext context,
      EvidenceExportStore.Work work,
      String code,
      boolean retryable) {
    var failed = store.generationFailed(context, work, code, retryable);
    if ("failed".equals(failed.status())) {
      var payload = json(Map.of(
          "exportId", failed.exportId(), "failureCode", code, "state", "failed"));
      evidence.record(context, GovernanceEvidence.auditOnly(new AuditRecord(
          "evidence.export.failed", 1, "evidence_export", failed.exportId(), null, payload)));
    }
    return new Result(failed.exportId(), failed.status(), null, null, code);
  }

  private Artifact serialize(
      EvidenceExportStore.Work work, List<Map<String, Object>> rows, long maximumBytes) {
    var filename = "careos-" + (work.projection().startsWith("history-") ? "history" : "audit")
        + "-" + work.exportId() + (work.format().equals("csv") ? ".csv" : ".jsonl");
    var output = new ByteArrayOutputStream();
    try {
      if (work.format().equals("csv")) {
        output.write(0xEF); output.write(0xBB); output.write(0xBF);
        var keys = orderedKeys(rows);
        line(output, keys.stream().map(this::csv).toList(), maximumBytes);
        for (var row : rows) {
          var values = new ArrayList<String>();
          for (var key : keys) values.add(csv(display(row.get(key))));
          line(output, values, maximumBytes);
        }
      } else {
        writeJsonLine(output, Map.of(
            "schema", work.projection(),
            "snapshotTime", work.snapshotTime(),
            "filterDigest", work.filterDigest()), maximumBytes);
        for (var row : rows) writeJsonLine(output, normalize(row), maximumBytes);
      }
    } catch (ExportLimitException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Evidence export serialization failed", exception);
    }
    var bytes = output.toByteArray();
    return new Artifact(
        bytes,
        work.format().equals("csv") ? "text/csv" : "application/x-ndjson",
        filename,
        sha256(bytes));
  }

  private void writeJsonLine(ByteArrayOutputStream output, Object value, long maximumBytes)
      throws Exception {
    output.write(mapper.writeValueAsBytes(value));
    output.write('\n');
    requireSize(output, maximumBytes);
  }

  private static void line(
      ByteArrayOutputStream output, List<String> values, long maximumBytes) {
    var bytes = (String.join(",", values) + "\r\n").getBytes(StandardCharsets.UTF_8);
    output.writeBytes(bytes);
    requireSize(output, maximumBytes);
  }

  private static void requireSize(ByteArrayOutputStream output, long maximumBytes) {
    if (output.size() > maximumBytes) {
      throw new ExportLimitException("evidence.export.byte_limit");
    }
  }

  private static List<String> orderedKeys(List<Map<String, Object>> rows) {
    var keys = new TreeSet<String>();
    rows.forEach(row -> keys.addAll(row.keySet()));
    return List.copyOf(keys);
  }

  private String csv(String value) {
    var normalized = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    normalized = normalized.codePoints()
        .filter(code -> !Character.isISOControl(code))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    if (normalized.stripLeading().matches("^[=+@-].*")) normalized = "'" + normalized;
    return "\"" + normalized.replace("\"", "\"\"") + "\"";
  }

  private String display(Object value) {
    if (value == null) return "";
    var normalized = normalizeValue(value);
    if (normalized instanceof String text) return text;
    try { return mapper.writeValueAsString(normalized); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private Map<String, Object> normalize(Map<String, Object> row) {
    var result = new TreeMap<String, Object>();
    row.forEach((key, value) -> result.put(key, normalizeValue(value)));
    return result;
  }

  private Object normalizeValue(Object value) {
    if (value == null || value instanceof Number || value instanceof Boolean) return value;
    var text = value.toString();
    if (text.startsWith("{") || text.startsWith("[")) {
      try { return mapper.readValue(text, Object.class); }
      catch (Exception exception) { throw new IllegalStateException("Stored JSON is invalid", exception); }
    }
    return text;
  }

  private ServiceIdentityAuthorizationRequest request(Command command, String operation) {
    return new ServiceIdentityAuthorizationRequest(
        command.organizationId(),
        command.presentedCredential(),
        "m1-export-worker-v1",
        command.correlationId(),
        new OperationKey(operation));
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private static String sha256(byte[] value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  public record Command(
      UUID organizationId,
      UUID exportId,
      String workerId,
      String presentedCredential,
      String correlationId) {}

  public record Result(
      UUID exportId, String status, Integer rowCount, Long byteCount, String failureCode) {}

  private record Artifact(byte[] bytes, String contentType, String filename, String digest) {}

  private static final class ExportLimitException extends RuntimeException {
    private final String code;
    private ExportLimitException(String code) { this.code = code; }
  }
}
