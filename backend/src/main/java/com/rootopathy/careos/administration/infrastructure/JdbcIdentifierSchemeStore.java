package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.IdentifierSchemeStore;
import com.rootopathy.careos.administration.domain.IdentifierSchemeDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentifierSchemeStore implements IdentifierSchemeStore {
  private final JdbcTemplate jdbc;

  public JdbcIdentifierSchemeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public IdentifierSchemeDirectory directory(AuthorizedTenantContext context) {
    var permissions = Set.copyOf(jdbc.queryForList(
        "SELECT DISTINCT rp.permission_key FROM organization_memberships m JOIN authorization_role_permissions rp ON rp.role_key=m.role_key WHERE m.organization_id=? AND m.user_id=? AND m.status='active' AND m.effective_from<=clock_timestamp() AND (m.effective_to IS NULL OR m.effective_to>clock_timestamp())",
        String.class,
        context.organizationId(),
        context.actorId()));
    var schemes = jdbc.query(
        "SELECT id,scheme_key,scope_type,scope_id,description,status,lock_version FROM identifier_schemes WHERE organization_id=? ORDER BY scheme_key",
        (row, number) -> new IdentifierSchemeDirectory.Scheme(
            row.getObject(1, UUID.class),
            row.getString(2),
            row.getString(3),
            row.getObject(4, UUID.class),
            row.getString(5),
            row.getString(6),
            row.getLong(7),
            versions(context.organizationId(), row.getObject(1, UUID.class))),
        context.organizationId());
    return new IdentifierSchemeDirectory(
        context.organizationId(),
        permissions.contains("identifier.scheme.manage"),
        permissions.contains("identifier.scheme.activate"),
        permissions.contains("identifier.scheme.retire"),
        schemes,
        Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
            .toInstant());
  }

  private List<IdentifierSchemeDirectory.Version> versions(UUID organizationId, UUID schemeId) {
    return jdbc.query(
        "SELECT id,version_number,prefix,pattern,alphabet,check_digit_algorithm,sequence_start,sequence_increment,padding,preview_samples,effective_from,status,lock_version FROM identifier_scheme_versions WHERE organization_id=? AND scheme_id=? ORDER BY version_number DESC",
        (row, number) -> {
          var samples = row.getArray(10);
          return new IdentifierSchemeDirectory.Version(
              row.getObject(1, UUID.class),
              row.getInt(2),
              row.getString(3),
              row.getString(4),
              row.getString(5),
              row.getString(6),
              row.getLong(7),
              row.getInt(8),
              row.getInt(9),
              List.of((String[]) samples.getArray()),
              row.getTimestamp(11).toInstant(),
              row.getString(12),
              row.getLong(13));
        },
        organizationId,
        schemeId);
  }

  @Override
  public Result create(AuthorizedTenantContext context, Draft draft) {
    var schemeId = jdbc.queryForObject(
        "INSERT INTO identifier_schemes(organization_id,scheme_key,scope_type,scope_id,description,created_by,updated_by) VALUES(?,?,?,?,?,?,?) RETURNING id",
        UUID.class,
        context.organizationId(),
        draft.schemeKey(),
        draft.scopeType(),
        draft.scopeId(),
        draft.description(),
        context.actorId(),
        context.actorId());
    var versionId = createVersionRow(context, schemeId, 1, draft.version());
    return new Result(
        schemeId,
        versionId,
        draft.version().effectiveFrom(),
        "none",
        "draft",
        0,
        directory(context));
  }

  @Override
  public Result createVersion(
      AuthorizedTenantContext context, UUID schemeId, long revision, VersionDraft draft) {
    var changed = jdbc.update(
        "UPDATE identifier_schemes SET lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND id=? AND status IN ('draft','active') AND lock_version=?",
        context.actorId(),
        context.organizationId(),
        schemeId,
        revision);
    if (changed != 1) throw new IllegalArgumentException("stale or unavailable scheme");
    var versionNumber = Objects.requireNonNull(jdbc.queryForObject(
        "SELECT coalesce(max(version_number),0)+1 FROM identifier_scheme_versions WHERE organization_id=? AND scheme_id=?",
        Integer.class,
        context.organizationId(),
        schemeId));
    var versionId = createVersionRow(context, schemeId, versionNumber, draft);
    return new Result(
        schemeId,
        versionId,
        draft.effectiveFrom(),
        "none",
        "draft",
        revision + 1,
        directory(context));
  }

  private UUID createVersionRow(
      AuthorizedTenantContext context, UUID schemeId, int versionNumber, VersionDraft draft) {
    return jdbc.queryForObject(
        "INSERT INTO identifier_scheme_versions(organization_id,scheme_id,version_number,prefix,pattern,alphabet,check_digit_algorithm,sequence_start,sequence_increment,padding,preview_samples,effective_from,next_sequence,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?::varchar[],?,?,?) RETURNING id",
        UUID.class,
        context.organizationId(),
        schemeId,
        versionNumber,
        draft.prefix(),
        draft.pattern(),
        draft.alphabet(),
        draft.checkDigitAlgorithm(),
        draft.sequenceStart(),
        draft.sequenceIncrement(),
        draft.padding(),
        draft.previewSamples().toArray(String[]::new),
        Timestamp.from(draft.effectiveFrom()),
        draft.sequenceStart(),
        context.actorId());
  }
}
