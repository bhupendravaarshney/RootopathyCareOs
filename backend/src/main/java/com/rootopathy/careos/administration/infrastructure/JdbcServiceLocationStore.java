package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.ServiceLocationStore;
import com.rootopathy.careos.administration.domain.ServiceLocationDirectory;
import com.rootopathy.careos.administration.domain.ServiceLocationDirectory.Location;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcServiceLocationStore implements ServiceLocationStore {
    private final JdbcTemplate jdbc;

    public JdbcServiceLocationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ServiceLocationDirectory directory(AuthorizedTenantContext context, UUID facilityId) {
        AuthorizedTenantTransactionGuard.requireBound(jdbc, context);
        var locations = jdbc.query(
                """
                SELECT id,unit_id,parent_id,address_id,location_code,location_type,name,
                       virtual_service_type,capacity,accessibility_notes,effective_from,effective_to,
                       status,lock_version,created_at,updated_at
                FROM service_locations
                WHERE organization_id=? AND facility_id=?
                ORDER BY lower(name),id
                """,
                (result, row) -> new Location(
                        result.getObject("id", UUID.class),
                        result.getObject("unit_id", UUID.class),
                        result.getObject("parent_id", UUID.class),
                        result.getObject("address_id", UUID.class),
                        result.getString("location_code"),
                        result.getString("location_type"),
                        result.getString("name"),
                        result.getString("virtual_service_type"),
                        result.getObject("capacity", Integer.class),
                        result.getString("accessibility_notes"),
                        result.getTimestamp("effective_from").toInstant(),
                        result.getTimestamp("effective_to") == null
                                ? null
                                : result.getTimestamp("effective_to").toInstant(),
                        result.getString("status"),
                        result.getLong("lock_version"),
                        result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("updated_at").toInstant()),
                context.organizationId(),
                facilityId);
        var permissions = permissions(context);
        return new ServiceLocationDirectory(
                context.organizationId(),
                facilityId,
                permissions.contains("network.structure.manage"),
                permissions.contains("network.structure.lifecycle"),
                locations,
                Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                        .toInstant());
    }

    @Override
    public Result create(AuthorizedTenantContext context, Draft draft) {
        AuthorizedTenantTransactionGuard.requireBound(jdbc, context);
        var id = jdbc.queryForObject(
                """
                INSERT INTO service_locations
                  (organization_id,facility_id,unit_id,parent_id,address_id,location_code,
                   location_type,name,virtual_service_type,capacity,accessibility_notes,
                   effective_from,effective_to,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?)
                RETURNING id
                """,
                UUID.class,
                context.organizationId(),
                draft.facilityId(),
                draft.unitId(),
                draft.parentId(),
                draft.addressId(),
                draft.locationCode(),
                draft.locationType(),
                draft.name(),
                draft.virtualServiceType(),
                draft.capacity(),
                draft.accessibilityNotes(),
                Timestamp.from(draft.effectiveFrom()),
                draft.effectiveTo() == null ? null : Timestamp.from(draft.effectiveTo()),
                context.actorId(),
                context.actorId());
        return new Result(directory(context, draft.facilityId()), id, 0, draft.parentId());
    }

    @Override
    public Result update(AuthorizedTenantContext context, UUID locationId, long revision, Draft draft) {
        AuthorizedTenantTransactionGuard.requireBound(jdbc, context);
        var changed = jdbc.update(
                """
                UPDATE service_locations
                SET unit_id=?,address_id=?,location_code=?,location_type=?,name=?,virtual_service_type=?,
                    capacity=?,accessibility_notes=?,effective_from=?,effective_to=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND facility_id=? AND id=? AND status='draft' AND lock_version=?
                """,
                draft.unitId(),
                draft.addressId(),
                draft.locationCode(),
                draft.locationType(),
                draft.name(),
                draft.virtualServiceType(),
                draft.capacity(),
                draft.accessibilityNotes(),
                Timestamp.from(draft.effectiveFrom()),
                draft.effectiveTo() == null ? null : Timestamp.from(draft.effectiveTo()),
                context.actorId(),
                context.organizationId(),
                draft.facilityId(),
                locationId,
                revision);
        if (changed != 1) throw state(context, draft.facilityId(), locationId);
        var parentId = jdbc.queryForObject(
                "SELECT parent_id FROM service_locations WHERE organization_id=? AND facility_id=? AND id=?",
                UUID.class,
                context.organizationId(),
                draft.facilityId(),
                locationId);
        return new Result(directory(context, draft.facilityId()), locationId, revision + 1, parentId);
    }

    @Override
    public ReparentResult reparent(
            AuthorizedTenantContext context,
            UUID facilityId,
            UUID locationId,
            long revision,
            UUID parentId,
            java.time.Instant effectiveFrom) {
        AuthorizedTenantTransactionGuard.requireBound(jdbc, context);
        var current = jdbc.query(
                "SELECT parent_id,status,lock_version FROM service_locations WHERE organization_id=? AND facility_id=? AND id=?",
                result -> result.next()
                        ? new Object[] {result.getObject(1, UUID.class), result.getString(2), result.getLong(3)}
                        : null,
                context.organizationId(), facilityId, locationId);
        if (current == null)
            throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                    com.rootopathy.careos.administration.application.ServiceLocationException.Reason.NOT_FOUND,
                    "Service location was not found.");
        if (!"draft".equals(current[1]) || (long) current[2] != revision)
            throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                    com.rootopathy.careos.administration.application.ServiceLocationException.Reason.STALE,
                    "Service location changed or is no longer an editable draft.");
        var previousParentId = (UUID) current[0];
        if (Objects.equals(previousParentId, parentId))
            throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                    com.rootopathy.careos.administration.application.ServiceLocationException.Reason.CONFLICT,
                    "The requested parent is already effective.");
        if (parentId != null) {
            var eligible = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM service_locations WHERE organization_id=? AND facility_id=? AND id=? AND status='draft')",
                    Boolean.class, context.organizationId(), facilityId, parentId));
            if (!eligible)
                throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                        com.rootopathy.careos.administration.application.ServiceLocationException.Reason.CONFLICT,
                        "Parent must be an eligible draft location in the same facility.");
            var cycle = Boolean.TRUE.equals(jdbc.queryForObject(
                    """
                    WITH RECURSIVE descendants(id) AS (
                      SELECT id FROM service_locations WHERE organization_id=? AND id=?
                      UNION ALL SELECT child.id FROM service_locations child JOIN descendants parent ON child.parent_id=parent.id
                       WHERE child.organization_id=?
                    ) SELECT EXISTS(SELECT 1 FROM descendants WHERE id=?)
                    """,
                    Boolean.class, context.organizationId(), locationId, context.organizationId(), parentId));
            if (cycle)
                throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                        com.rootopathy.careos.administration.application.ServiceLocationException.Reason.CONFLICT,
                        "Reparenting would create a hierarchy cycle.");
            var ancestorDepth = jdbc.queryForObject(
                    """
                    WITH RECURSIVE ancestors(id,parent_id,depth) AS (
                      SELECT id,parent_id,1 FROM service_locations WHERE organization_id=? AND id=?
                      UNION ALL SELECT p.id,p.parent_id,a.depth+1 FROM service_locations p JOIN ancestors a ON p.id=a.parent_id
                       WHERE p.organization_id=? AND a.depth<9
                    ) SELECT coalesce(max(depth),0) FROM ancestors
                    """,
                    Integer.class, context.organizationId(), parentId, context.organizationId());
            var subtreeDepth = jdbc.queryForObject(
                    """
                    WITH RECURSIVE descendants(id,depth) AS (
                      SELECT id,1 FROM service_locations WHERE organization_id=? AND id=?
                      UNION ALL SELECT child.id,parent.depth+1 FROM service_locations child JOIN descendants parent ON child.parent_id=parent.id
                       WHERE child.organization_id=? AND parent.depth<9
                    ) SELECT coalesce(max(depth),1) FROM descendants
                    """,
                    Integer.class, context.organizationId(), locationId, context.organizationId());
            if (ancestorDepth + subtreeDepth > 8)
                throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                        com.rootopathy.careos.administration.application.ServiceLocationException.Reason.CONFLICT,
                        "Reparenting would exceed hierarchy depth 8.");
        }
        var nextRevision = revision + 1;
        jdbc.update(
                "INSERT INTO service_location_parent_history(organization_id,location_id,previous_parent_id,parent_id,effective_from,lock_version,changed_by) VALUES(?,?,?,?,?,?,?)",
                context.organizationId(), locationId, previousParentId, parentId, Timestamp.from(effectiveFrom), nextRevision, context.actorId());
        var changed = jdbc.update(
                "UPDATE service_locations SET parent_id=?,lock_version=?,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND facility_id=? AND id=? AND status='draft' AND lock_version=?",
                parentId, nextRevision, context.actorId(), context.organizationId(), facilityId, locationId, revision);
        if (changed != 1)
            throw new com.rootopathy.careos.administration.application.ServiceLocationException(
                    com.rootopathy.careos.administration.application.ServiceLocationException.Reason.STALE,
                    "Service location changed before reparenting.");
        return new ReparentResult(directory(context, facilityId), locationId, previousParentId, parentId, nextRevision, effectiveFrom);
    }

    private com.rootopathy.careos.administration.application.ServiceLocationException state(
            AuthorizedTenantContext context, UUID facilityId, UUID locationId) {
        var exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM service_locations WHERE organization_id=? AND facility_id=? AND id=?)",
                Boolean.class, context.organizationId(), facilityId, locationId));
        return new com.rootopathy.careos.administration.application.ServiceLocationException(
                exists
                        ? com.rootopathy.careos.administration.application.ServiceLocationException.Reason.STALE
                        : com.rootopathy.careos.administration.application.ServiceLocationException.Reason.NOT_FOUND,
                exists ? "Service location changed or is no longer an editable draft." : "Service location was not found.");
    }

    @Override
    public LifecycleResult transition(AuthorizedTenantContext context, UUID facilityId, UUID locationId,
            long revision, String fromState, String toState) {
        AuthorizedTenantTransactionGuard.requireBound(jdbc, context);
        if (!Set.of("draft:active", "active:suspended", "suspended:active").contains(fromState + ":" + toState))
            throw new IllegalArgumentException("unsupported service location lifecycle transition");
        var state = lifecycleState(context, facilityId, locationId);
        requireState(state, revision, fromState);
        if ("active".equals(toState)) {
            if (!Set.of("under_review", "active").contains(state[5])) conflict("Service location activation requires an eligible facility.");
            var now = java.time.Instant.now();
            if (((java.time.Instant) state[3]).isAfter(now) || state[4] != null && !((java.time.Instant) state[4]).isAfter(now)) conflict("Service location activation requires a current effective range.");
            if (state[0] != null && !"active".equals(state[6])) conflict("Service location activation requires an active parent.");
        }
        if ("active".equals(fromState) && "suspended".equals(toState) && hasDescendant(context, locationId, "status='active'"))
            conflict("Service location suspension requires all descendants to be non-active.");
        var changed = jdbc.update("UPDATE service_locations SET status=?,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND facility_id=? AND id=? AND status=? AND lock_version=?",
                toState, context.actorId(), context.organizationId(), facilityId, locationId, fromState, revision);
        if (changed != 1) stale();
        return new LifecycleResult(directory(context, facilityId), locationId, (UUID) state[0], fromState, revision + 1);
    }

    @Override
    public LifecycleResult close(AuthorizedTenantContext context, UUID facilityId, UUID locationId,
            long revision, java.time.Instant effectiveTo) {
        AuthorizedTenantTransactionGuard.requireBound(jdbc, context);
        var state = lifecycleState(context, facilityId, locationId);
        if (state == null) notFound();
        if (!Set.of("active", "suspended").contains(state[1]) || (long) state[2] != revision) stale();
        if (!effectiveTo.isAfter((java.time.Instant) state[3])) conflict("Service location closure must end after its effective start.");
        if (hasDescendant(context, locationId, "status<>'closed'")) conflict("Service location closure requires all descendants to be closed.");
        var changed = jdbc.update("UPDATE service_locations SET status='closed',effective_to=?,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND facility_id=? AND id=? AND status IN ('active','suspended') AND lock_version=?",
                Timestamp.from(effectiveTo), context.actorId(), context.organizationId(), facilityId, locationId, revision);
        if (changed != 1) stale();
        return new LifecycleResult(directory(context, facilityId), locationId, (UUID) state[0], (String) state[1], revision + 1);
    }

    private Object[] lifecycleState(AuthorizedTenantContext context, UUID facilityId, UUID locationId) {
        return jdbc.query("""
                SELECT l.parent_id,l.status,l.lock_version,l.effective_from,l.effective_to,f.status,
                       CASE WHEN l.parent_id IS NULL THEN NULL ELSE p.status END
                FROM service_locations l JOIN facilities f ON f.organization_id=l.organization_id AND f.id=l.facility_id
                LEFT JOIN service_locations p ON p.organization_id=l.organization_id AND p.id=l.parent_id
                WHERE l.organization_id=? AND l.facility_id=? AND l.id=?
                """, result -> result.next() ? new Object[] {result.getObject(1, UUID.class), result.getString(2), result.getLong(3), result.getTimestamp(4).toInstant(), result.getTimestamp(5) == null ? null : result.getTimestamp(5).toInstant(), result.getString(6), result.getString(7)} : null,
                context.organizationId(), facilityId, locationId);
    }

    private boolean hasDescendant(AuthorizedTenantContext context, UUID locationId, String predicate) {
        return Boolean.TRUE.equals(jdbc.queryForObject("WITH RECURSIVE descendants(id,status) AS (SELECT id,status FROM service_locations WHERE organization_id=? AND parent_id=? UNION ALL SELECT l.id,l.status FROM service_locations l JOIN descendants d ON l.parent_id=d.id WHERE l.organization_id=?) SELECT EXISTS(SELECT 1 FROM descendants WHERE " + predicate + ")",
                Boolean.class, context.organizationId(), locationId, context.organizationId()));
    }

    private static void requireState(Object[] state, long revision, String fromState) {
        if (state == null) notFound();
        if (!fromState.equals(state[1]) || (long) state[2] != revision) stale();
    }
    private static void notFound() { throw new com.rootopathy.careos.administration.application.ServiceLocationException(com.rootopathy.careos.administration.application.ServiceLocationException.Reason.NOT_FOUND, "Service location was not found."); }
    private static void stale() { throw new com.rootopathy.careos.administration.application.ServiceLocationException(com.rootopathy.careos.administration.application.ServiceLocationException.Reason.STALE, "Service location changed or is no longer eligible for this lifecycle transition."); }
    private static void conflict(String message) { throw new com.rootopathy.careos.administration.application.ServiceLocationException(com.rootopathy.careos.administration.application.ServiceLocationException.Reason.CONFLICT, message); }

    private Set<String> permissions(AuthorizedTenantContext context) {
        return Set.copyOf(jdbc.queryForList(
                """
                SELECT DISTINCT permissions.permission_key
                FROM organization_memberships memberships
                JOIN authorization_roles roles ON roles.role_key=memberships.role_key
                JOIN authorization_role_permissions role_permissions ON role_permissions.role_key=roles.role_key
                JOIN authorization_permissions permissions
                  ON permissions.permission_key=role_permissions.permission_key
                 AND permissions.registry_version=roles.registry_version
                WHERE memberships.organization_id=? AND memberships.user_id=?
                  AND memberships.status='active' AND memberships.effective_from<=clock_timestamp()
                  AND (memberships.effective_to IS NULL OR memberships.effective_to>clock_timestamp())
                  AND roles.registry_version='m1-candidate-1' AND roles.status='active'
                  AND permissions.status='active'
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }
}
