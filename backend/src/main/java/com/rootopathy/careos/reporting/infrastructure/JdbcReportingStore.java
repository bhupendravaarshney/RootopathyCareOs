package com.rootopathy.careos.reporting.infrastructure;

import com.rootopathy.careos.reporting.application.ReportingException;
import com.rootopathy.careos.reporting.application.ReportingStore;
import com.rootopathy.careos.reporting.domain.ReportingScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed Module 12 aggregate reporting and governed export-request workflow. */
@Repository
public class JdbcReportingStore implements ReportingStore {
    private static final String OUTBOX_EVENT = "m12.reporting-artifact-changed.v1";
    private static final Set<String> REPORT_KEYS = Set.of(
            "operational", "clinical_safety", "outcomes", "workforce_governance",
            "access_security", "ai_governance", "financial");
    private static final Map<String, String> PURPOSES = Map.of(
            "operational", "operations_management",
            "clinical_safety", "clinical_safety_oversight",
            "outcomes", "outcomes_evaluation",
            "workforce_governance", "workforce_governance",
            "access_security", "security_investigation",
            "ai_governance", "ai_governance",
            "financial", "financial_governance");
    private static final Map<String, String> SCREEN_REPORTS = Map.of(
            "P12-02", "operational",
            "P12-03", "clinical_safety",
            "P12-04", "outcomes",
            "P12-05", "workforce_governance",
            "P12-06", "access_security",
            "P12-07", "ai_governance",
            "P12-08", "financial");

    private final JdbcTemplate jdbc;

    public JdbcReportingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var projected = switch (query.screenId()) {
            case "P12-01" -> reportRunRows(context, "P12-01", null);
            case "P12-02", "P12-03", "P12-04", "P12-05", "P12-06", "P12-07", "P12-08" ->
                    reportRunRows(context, query.screenId(), SCREEN_REPORTS.get(query.screenId()));
            case "P12-09" -> scheduleAndExportRows(context);
            case "P12-10" -> historyRows(context);
            default -> throw notFound("The requested reporting screen does not exist.");
        };
        var rows = projected.stream()
                .filter(row -> matches(query, row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context, query.screenId()), columns(query.screenId()), rows,
                notices(query.screenId()), databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class, context.organizationId(), context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "run-report" -> runReport(context, command);
            case "create-report-schedule" -> createSchedule(context, command);
            case "pause-report-schedule" -> transitionSchedule(context, command, "active", "paused",
                    "reporting.schedule.paused");
            case "resume-report-schedule" -> transitionSchedule(context, command, "paused", "active",
                    "reporting.schedule.resumed");
            case "cancel-report-schedule" -> cancelSchedule(context, command);
            case "request-report-export" -> requestExport(context, command);
            default -> throw notFound("The requested reporting action does not exist.");
        };
    }

    private MutationResult runReport(
            AuthorizedTenantContext context, MutationCommand command) {
        var reportKey = SCREEN_REPORTS.get(command.screenId());
        if (reportKey == null) throw invalid("This screen does not define a report family.");
        var fromDate = date(field(command, "periodStart"), "periodStart");
        var throughDate = date(field(command, "periodEnd"), "periodEnd");
        var inclusiveDays = ChronoUnit.DAYS.between(fromDate, throughDate) + 1;
        if (inclusiveDays < 1 || inclusiveDays > 366) {
            throw invalid("The reporting period must contain 1 to 366 days.");
        }
        if (fromDate.isAfter(command.now().atZone(ZoneOffset.UTC).toLocalDate())) {
            throw invalid("The reporting period cannot begin in the future.");
        }
        var periodStart = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        var periodEnd = throughDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var purpose = PURPOSES.get(reportKey);
        var parametersDigest = sha256(reportKey, periodStart, periodEnd, purpose);
        var runId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO report_runs(
                    id,organization_id,report_key,period_start,period_end,purpose_key,
                    parameters_digest,metric_count,status,created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,0,'building',?,?,?,?)
                """,
                runId, context.organizationId(), reportKey, Timestamp.from(periodStart),
                Timestamp.from(periodEnd), purpose, parametersDigest,
                Timestamp.from(command.now()), context.actorId(), Timestamp.from(command.now()),
                context.actorId());
        var aggregate = aggregate(context, reportKey, periodStart, periodEnd);
        var sequence = 0;
        for (var entry : aggregate.entrySet()) {
            var metricId = UuidV7Generator.randomUuid();
            jdbc.update(
                    """
                    INSERT INTO report_run_metrics(
                        id,organization_id,report_run_id,metric_sequence,metric_key,metric_value,
                        status,created_at,created_by,updated_at,updated_by)
                    VALUES (?,?,?,?,?,?,'final',?,?,?,?)
                    """,
                    metricId, context.organizationId(), runId, ++sequence, entry.getKey(), entry.getValue(),
                    Timestamp.from(command.now()), context.actorId(), Timestamp.from(command.now()),
                    context.actorId());
        }
        var changed = jdbc.update(
                """
                UPDATE report_runs SET metric_count=?,snapshot_digest=careos_m12_report_digest(organization_id,id),
                    completed_at=clock_timestamp(),status='completed',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='building' AND lock_version=0
                """,
                aggregate.size(), context.actorId(), context.organizationId(), runId);
        requireChanged(changed, "The aggregate report snapshot could not be completed.");
        return result(runId, runId, "report_run", "reporting.run.completed",
                "building", "completed", 1, 201);
    }

    private MutationResult createSchedule(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = UuidV7Generator.randomUuid();
        var scheduleCode = upperCode(field(command, "scheduleCode"), "scheduleCode", 4, 64);
        var reportKey = reportKey(field(command, "reportKey"));
        var format = oneOf(field(command, "format"), "format", "csv", "json");
        var cadence = oneOf(field(command, "cadence"), "cadence", "daily", "weekly", "monthly");
        var lookback = integer(field(command, "lookbackDays"), "lookbackDays", 1, 366);
        var timezone = bounded(field(command, "timezone"), 3, 80, "timezone");
        if (!timezone.equals("UTC") && !timezone.matches("[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+")) {
            throw invalid("timezone must be UTC or an IANA-style zone identifier.");
        }
        var nextRun = instant(field(command, "nextRunAt"), "nextRunAt");
        if (!nextRun.isAfter(command.now()) || nextRun.isAfter(command.now().plus(366, ChronoUnit.DAYS))) {
            throw invalid("nextRunAt must be in the next 366 days.");
        }
        var purpose = PURPOSES.get(reportKey);
        var digest = sha256(reportKey, format, cadence, lookback, timezone, purpose);
        jdbc.update(
                """
                INSERT INTO report_schedules(
                    id,organization_id,schedule_code,report_key,format_key,cadence,lookback_days,
                    timezone,purpose_key,parameters_digest,next_run_at,last_transition_at,
                    last_transition_by,last_transition_reason,status,created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,?,?,'active',?,?,?,?)
                """,
                id, context.organizationId(), scheduleCode, reportKey, format, cadence, lookback,
                timezone, purpose, digest, Timestamp.from(nextRun), Timestamp.from(command.now()),
                context.actorId(), command.reason(), Timestamp.from(command.now()), context.actorId(),
                Timestamp.from(command.now()), context.actorId());
        return result(id, null, "report_schedule", "reporting.schedule.created",
                "none", "active", 0, 201);
    }

    private MutationResult transitionSchedule(
            AuthorizedTenantContext context,
            MutationCommand command,
            String expectedStatus,
            String nextStatus,
            String event) {
        var schedule = lockSchedule(context, command.targetId());
        requireRevision(schedule.revision(), command.expectedRevision(), "report schedule");
        if (!schedule.status().equals(expectedStatus)) {
            throw conflict("The report schedule is not in the required state.");
        }
        var nextRun = nextStatus.equals("active") && !schedule.nextRunAt().isAfter(command.now())
                ? nextRun(command.now(), schedule.cadence())
                : schedule.nextRunAt();
        var changed = jdbc.update(
                """
                UPDATE report_schedules SET status=?,next_run_at=?,last_transition_at=clock_timestamp(),
                    last_transition_by=?,last_transition_reason=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status=?
                """,
                nextStatus, Timestamp.from(nextRun), context.actorId(), command.reason(), context.actorId(),
                context.organizationId(), schedule.id(), schedule.revision(), expectedStatus);
        requireChanged(changed, "The report schedule changed; reload before continuing.");
        return result(schedule.id(), null, "report_schedule", event,
                expectedStatus, nextStatus, schedule.revision() + 1, 200);
    }

    private MutationResult cancelSchedule(
            AuthorizedTenantContext context, MutationCommand command) {
        var schedule = lockSchedule(context, command.targetId());
        requireRevision(schedule.revision(), command.expectedRevision(), "report schedule");
        if (!Set.of("active", "paused").contains(schedule.status())) {
            throw conflict("Only an active or paused report schedule can be cancelled.");
        }
        var changed = jdbc.update(
                """
                UPDATE report_schedules SET status='cancelled',last_transition_at=clock_timestamp(),
                    last_transition_by=?,last_transition_reason=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status IN ('active','paused')
                """,
                context.actorId(), command.reason(), context.actorId(), context.organizationId(),
                schedule.id(), schedule.revision());
        requireChanged(changed, "The report schedule changed; reload before continuing.");
        return result(schedule.id(), null, "report_schedule", "reporting.schedule.cancelled",
                schedule.status(), "cancelled", schedule.revision() + 1, 200);
    }

    private MutationResult requestExport(
            AuthorizedTenantContext context, MutationCommand command) {
        var run = readRun(context, command.targetId());
        requireRevision(run.revision(), command.expectedRevision(), "report run");
        if (!run.status().equals("completed")) {
            throw conflict("Only a completed aggregate report can be exported.");
        }
        var format = oneOf(field(command, "format"), "format", "csv", "json");
        var id = UuidV7Generator.randomUuid();
        var filtersDigest = sha256(run.snapshotDigest(), format, run.purpose());
        var expires = command.now().plus(1, ChronoUnit.HOURS);
        jdbc.update(
                """
                INSERT INTO report_exports(
                    id,organization_id,report_run_id,report_key,format_key,purpose_key,
                    filters_digest,requested_at,requested_by,expires_at,status,
                    created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'requested',?,?,?,?)
                """,
                id, context.organizationId(), run.id(), run.reportKey(), format, run.purpose(),
                filtersDigest, Timestamp.from(command.now()), context.actorId(), Timestamp.from(expires),
                Timestamp.from(command.now()), context.actorId(), Timestamp.from(command.now()),
                context.actorId());
        return result(id, run.id(), "report_export", "reporting.export.requested",
                "none", "requested", 0, 201);
    }

    private LinkedHashMap<String, Long> aggregate(
            AuthorizedTenantContext context, String reportKey, Instant start, Instant end) {
        var organization = context.organizationId();
        var from = Timestamp.from(start);
        var to = Timestamp.from(end);
        var metrics = new LinkedHashMap<String, Long>();
        switch (reportKey) {
            case "operational" -> {
                metrics.put("appointment_total", scalar(
                        "SELECT count(*) FROM appointments WHERE organization_id=? AND starts_at>=? AND starts_at<?",
                        organization, from, to));
                metrics.put("appointment_confirmed", scalar(
                        "SELECT count(*) FROM appointments WHERE organization_id=? AND starts_at>=? AND starts_at<? AND status='confirmed'",
                        organization, from, to));
                metrics.put("appointment_no_show", scalar(
                        "SELECT count(*) FROM appointments WHERE organization_id=? AND starts_at>=? AND starts_at<? AND status='no_show'",
                        organization, from, to));
                metrics.put("encounter_completed", scalar(
                        "SELECT count(*) FROM encounters WHERE organization_id=? AND completed_at>=? AND completed_at<? AND status='completed'",
                        organization, from, to));
            }
            case "clinical_safety" -> {
                metrics.put("result_flag_total", scalar(
                        "SELECT count(*) FROM result_flags WHERE organization_id=? AND detected_at>=? AND detected_at<?",
                        organization, from, to));
                metrics.put("result_flag_open", scalar(
                        "SELECT count(*) FROM result_flags WHERE organization_id=? AND detected_at>=? AND detected_at<? AND status<>'resolved'",
                        organization, from, to));
                metrics.put("critical_flag_open", scalar(
                        "SELECT count(*) FROM result_flags WHERE organization_id=? AND detected_at>=? AND detected_at<? AND flag_kind='critical' AND status<>'resolved'",
                        organization, from, to));
                metrics.put("overdue_flag_open", scalar(
                        "SELECT count(*) FROM result_flags WHERE organization_id=? AND detected_at>=? AND detected_at<? AND acknowledgement_due_at<clock_timestamp() AND status='open'",
                        organization, from, to));
            }
            case "outcomes" -> {
                metrics.put("measurement_total", scalar(
                        "SELECT count(*) FROM outcome_measurements WHERE organization_id=? AND observed_at>=? AND observed_at<?",
                        organization, from, to));
                metrics.put("escalation_total", scalar(
                        "SELECT count(*) FROM escalation_events WHERE organization_id=? AND triggered_at>=? AND triggered_at<?",
                        organization, from, to));
                metrics.put("escalation_open", scalar(
                        "SELECT count(*) FROM escalation_events WHERE organization_id=? AND triggered_at>=? AND triggered_at<? AND status<>'resolved'",
                        organization, from, to));
                metrics.put("followup_active", scalar(
                        "SELECT count(*) FROM followup_plans WHERE organization_id=? AND created_at<? AND status='active'",
                        organization, to));
            }
            case "workforce_governance" -> {
                metrics.put("workforce_active", scalar(
                        "SELECT count(*) FROM workforce_members WHERE organization_id=? AND created_at<? AND lifecycle_state='active'",
                        organization, to));
                metrics.put("workforce_suspended", scalar(
                        "SELECT count(*) FROM workforce_members WHERE organization_id=? AND created_at<? AND lifecycle_state='suspended'",
                        organization, to));
                metrics.put("credential_expiring", scalar(
                        "SELECT count(*) FROM practitioner_credentials WHERE organization_id=? AND expires_on>=? AND expires_on<? AND status NOT IN ('revoked','superseded')",
                        organization, Date.valueOf(start.atZone(ZoneOffset.UTC).toLocalDate()),
                        Date.valueOf(end.atZone(ZoneOffset.UTC).toLocalDate())));
                metrics.put("credential_attention", scalar(
                        "SELECT count(*) FROM practitioner_credentials WHERE organization_id=? AND created_at<? AND status IN ('evidence_pending','submitted','in_review','more_information_required','suspended','expired')",
                        organization, to));
            }
            case "access_security" -> {
                metrics.put("audit_event_total", scalar(
                        "SELECT count(*) FROM audit_events WHERE organization_id=? AND occurred_at>=? AND occurred_at<?",
                        organization, from, to));
                metrics.put("distinct_actor_total", scalar(
                        "SELECT count(DISTINCT actor_user_id) FROM audit_events WHERE organization_id=? AND occurred_at>=? AND occurred_at<? AND actor_user_id IS NOT NULL",
                        organization, from, to));
                metrics.put("sensitive_operation_total", scalar(
                        "SELECT count(*) FROM audit_events WHERE organization_id=? AND occurred_at>=? AND occurred_at<? AND (event_name LIKE '%access%' OR event_name LIKE '%export%' OR event_name LIKE '%security%')",
                        organization, from, to));
            }
            case "ai_governance" -> {
                metrics.put("ai_session_total", scalar(
                        "SELECT count(*) FROM ai_sessions WHERE organization_id=? AND created_at>=? AND created_at<?",
                        organization, from, to));
                metrics.put("ai_session_failed", scalar(
                        "SELECT count(*) FROM ai_sessions WHERE organization_id=? AND created_at>=? AND created_at<? AND status='failed'",
                        organization, from, to));
                metrics.put("safety_flag_open", scalar(
                        "SELECT count(*) FROM ai_safety_flags WHERE organization_id=? AND created_at>=? AND created_at<? AND state<>'resolved'",
                        organization, from, to));
                metrics.put("safety_flag_critical", scalar(
                        "SELECT count(*) FROM ai_safety_flags WHERE organization_id=? AND created_at>=? AND created_at<? AND severity IN ('critical','emergency') AND state<>'resolved'",
                        organization, from, to));
            }
            case "financial" -> {
                metrics.put("invoice_total", scalar(
                        "SELECT count(*) FROM invoices WHERE organization_id=? AND issued_at>=? AND issued_at<?",
                        organization, from, to));
                metrics.put("invoice_open", scalar(
                        "SELECT count(*) FROM invoices WHERE organization_id=? AND issued_at>=? AND issued_at<? AND status IN ('issued','partially_paid')",
                        organization, from, to));
                metrics.put("outstanding_balance_minor", scalar(
                        "SELECT coalesce(sum(balance_minor),0) FROM invoices WHERE organization_id=? AND issued_at>=? AND issued_at<? AND status IN ('issued','partially_paid')",
                        organization, from, to));
                metrics.put("reconciliation_exception", scalar(
                        "SELECT count(*) FROM reconciliations WHERE organization_id=? AND created_at>=? AND created_at<? AND status='exception'",
                        organization, from, to));
            }
            default -> throw invalid("The report family is unsupported.");
        }
        return metrics;
    }

    private List<ReportingScreen.Row> scheduleAndExportRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<ReportingScreen.Row>();
        rows.addAll(scheduleRows(context, "P12-09"));
        rows.addAll(reportRunRows(context, "P12-09", null));
        rows.addAll(exportRows(context, "P12-09"));
        return rows;
    }

    private List<ReportingScreen.Row> historyRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<ReportingScreen.Row>();
        rows.addAll(reportRunRows(context, "P12-10", null));
        rows.addAll(scheduleRows(context, "P12-10"));
        rows.addAll(exportRows(context, "P12-10"));
        rows.sort((left, right) -> right.values().getOrDefault("recordedAt", "")
                .compareTo(left.values().getOrDefault("recordedAt", "")));
        return rows;
    }

    private List<ReportingScreen.Row> reportRunRows(
            AuthorizedTenantContext context, String screenId, String reportKey) {
        return jdbc.query(
                """
                SELECT run.id,run.report_key,run.period_start,run.period_end,run.purpose_key,
                       run.metric_count,run.snapshot_digest,run.completed_at,run.status,
                       run.lock_version,run.created_at,
                       (SELECT string_agg(metric.metric_key||'='||metric.metric_value::text,', '
                                          ORDER BY metric.metric_sequence)
                          FROM report_run_metrics metric
                         WHERE metric.organization_id=run.organization_id
                           AND metric.report_run_id=run.id) metric_summary
                FROM report_runs run
                WHERE run.organization_id=? AND (CAST(? AS varchar) IS NULL OR run.report_key=?)
                ORDER BY run.created_at DESC,run.id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    var status = rs.getString("status");
                    var actions = screenId.equals("P12-09") && status.equals("completed")
                            ? List.of("request-report-export") : List.<String>of();
                    return row(screenId, id, id, null, null, status, rs.getLong("lock_version"),
                            values("artifact", "report_run", "reportFamily", rs.getString("report_key"),
                                    "period", instantText(rs.getObject("period_start")) + " to "
                                            + instantText(rs.getObject("period_end")),
                                    "purpose", rs.getString("purpose_key"),
                                    "metrics", rs.getString("metric_summary"),
                                    "digest", rs.getString("snapshot_digest"),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            actions);
                },
                context.organizationId(), reportKey, reportKey);
    }

    private List<ReportingScreen.Row> scheduleRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT id,schedule_code,report_key,format_key,cadence,lookback_days,timezone,
                       purpose_key,next_run_at,status,lock_version,created_at
                FROM report_schedules WHERE organization_id=?
                ORDER BY created_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    var status = rs.getString("status");
                    var actions = new ArrayList<String>();
                    if (screenId.equals("P12-09") && status.equals("active")) {
                        actions.add("pause-report-schedule");
                        actions.add("cancel-report-schedule");
                    } else if (screenId.equals("P12-09") && status.equals("paused")) {
                        actions.add("resume-report-schedule");
                        actions.add("cancel-report-schedule");
                    }
                    return row(screenId, id, null, id, null, status, rs.getLong("lock_version"),
                            values("artifact", "report_schedule", "code", rs.getString("schedule_code"),
                                    "reportFamily", rs.getString("report_key"),
                                    "format", rs.getString("format_key"),
                                    "cadence", rs.getString("cadence"),
                                    "lookbackDays", rs.getString("lookback_days"),
                                    "timezone", rs.getString("timezone"),
                                    "purpose", rs.getString("purpose_key"),
                                    "nextRunAt", instantText(rs.getObject("next_run_at")),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            List.copyOf(actions));
                },
                context.organizationId());
    }

    private List<ReportingScreen.Row> exportRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT id,report_run_id,report_key,format_key,purpose_key,requested_at,
                       expires_at,status,lock_version,artifact_digest,failure_code,created_at
                FROM report_exports WHERE organization_id=?
                ORDER BY created_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    return row(screenId, id, rs.getObject("report_run_id", UUID.class), null, id,
                            rs.getString("status"), rs.getLong("lock_version"),
                            values("artifact", "report_export", "reportFamily", rs.getString("report_key"),
                                    "format", rs.getString("format_key"),
                                    "purpose", rs.getString("purpose_key"),
                                    "requestedAt", instantText(rs.getObject("requested_at")),
                                    "expiresAt", instantText(rs.getObject("expires_at")),
                                    "digest", rs.getString("artifact_digest"),
                                    "failure", rs.getString("failure_code"),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            List.of());
                },
                context.organizationId());
    }

    private List<ReportingScreen.Metric> metrics(
            AuthorizedTenantContext context, String screenId) {
        var reportKey = SCREEN_REPORTS.get(screenId);
        if (reportKey != null) {
            var end = databaseNow();
            var start = end.minus(30, ChronoUnit.DAYS);
            return aggregate(context, reportKey, start, end).entrySet().stream()
                    .map(entry -> metric(entry.getKey(), label(entry.getKey()), entry.getValue(),
                            entry.getKey().contains("critical") || entry.getKey().contains("overdue")
                                    || entry.getKey().contains("exception") ? "warning" : "info"))
                    .toList();
        }
        var summary = jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM report_runs WHERE organization_id=? AND status='completed') completed_runs,
                  (SELECT count(*) FROM report_schedules WHERE organization_id=? AND status='active') active_schedules,
                  (SELECT count(*) FROM report_exports WHERE organization_id=? AND status='requested') requested_exports,
                  (SELECT count(*) FROM report_exports WHERE organization_id=? AND status='failed') failed_exports
                """,
                context.organizationId(), context.organizationId(), context.organizationId(),
                context.organizationId());
        return List.of(
                metric("completedRuns", "Completed runs", number(summary.get("completed_runs")), "success"),
                metric("activeSchedules", "Active schedules", number(summary.get("active_schedules")), "info"),
                metric("requestedExports", "Requested exports", number(summary.get("requested_exports")), "warning"),
                metric("failedExports", "Failed exports", number(summary.get("failed_exports")), "danger"));
    }

    private static List<ReportingScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P12-09" -> List.of(column("artifact", "Artifact"), column("code", "Code"),
                    column("reportFamily", "Report family"), column("format", "Format"),
                    column("nextRunAt", "Next run"));
            case "P12-10" -> List.of(column("artifact", "Artifact"),
                    column("reportFamily", "Report family"), column("purpose", "Purpose"),
                    column("recordedAt", "Recorded at"));
            default -> List.of(column("reportFamily", "Report family"), column("period", "Period"),
                    column("metrics", "Aggregate metrics"), column("recordedAt", "Recorded at"));
        };
    }

    private static List<ReportingScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<ReportingScreen.Notice>();
        notices.add(notice("info", "Minimum necessary",
                "Reports contain aggregate metrics only; source clinical, identity and payment details are excluded."));
        if (screenId.equals("P12-09")) {
            notices.add(notice("warning", "Private worker required",
                    "Schedules and export requests remain pending until an authorized private worker and storage policy are active."));
        }
        notices.add(notice("info", "Spreadsheet safety",
                "CSV generation neutralizes formula prefixes before standards-compliant quoting."));
        return List.copyOf(notices);
    }

    private static boolean matches(ScreenQuery query, ReportingScreen.Row row) {
        if (query.reportRunId() != null && !query.reportRunId().equals(row.reportRunId())) return false;
        if (query.status() != null && !query.status().equalsIgnoreCase(row.status())) return false;
        if (query.search() == null) return true;
        var needle = query.search().toLowerCase(Locale.ROOT);
        return row.values().values().stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private ScheduleRecord lockSchedule(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,cadence,next_run_at,status,lock_version
                FROM report_schedules WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new ScheduleRecord(
                        rs.getObject("id", UUID.class), rs.getString("cadence"),
                        rs.getTimestamp("next_run_at").toInstant(), rs.getString("status"),
                        rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The report schedule is unavailable.");
        return rows.getFirst();
    }

    private RunRecord readRun(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,report_key,purpose_key,snapshot_digest,status,lock_version
                FROM report_runs WHERE organization_id=? AND id=?
                """,
                (rs, index) -> new RunRecord(
                        rs.getObject("id", UUID.class), rs.getString("report_key"),
                        rs.getString("purpose_key"), rs.getString("snapshot_digest"),
                        rs.getString("status"), rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The report run is unavailable.");
        return rows.getFirst();
    }

    private long scalar(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class, context.organizationId(), context.actorId()));
        if (!bound) {
            throw notFound("The reporting resource is unavailable or is not assigned to this account.");
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private static ReportingScreen.Row row(
            String screenId,
            UUID id,
            UUID reportRunId,
            UUID reportScheduleId,
            UUID reportExportId,
            String status,
            long revision,
            Map<String, String> values,
            List<String> actions) {
        return new ReportingScreen.Row(
                id, reportRunId, reportScheduleId, reportExportId, status, revision,
                etag(screenId, id, revision), values, actions);
    }

    private static String etag(String screenId, UUID id, long revision) {
        return "\"m12:" + screenId + ":" + id + ":" + revision + "\"";
    }

    private static MutationResult result(
            UUID subjectId,
            UUID reportRunId,
            String subjectType,
            String auditEvent,
            String fromState,
            String toState,
            long revision,
            int statusCode) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("artifactId", subjectId);
        payload.put("artifactType", subjectType);
        payload.put("fromState", fromState);
        payload.put("toState", toState);
        payload.put("revision", revision);
        var immutable = Map.<String, Object>copyOf(payload);
        return new MutationResult(
                subjectId, reportRunId, subjectType, auditEvent, OUTBOX_EVENT,
                "reporting_artifact", subjectId, immutable, immutable, statusCode, revision);
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must be an ISO local date.");
        }
    }

    private static Instant instant(String value, String field) {
        try {
            if (value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw invalid(field + " must be an ISO date-time.");
        }
    }

    private static int integer(String value, String field, int minimum, int maximum) {
        try {
            var parsed = Integer.parseInt(value.strip());
            if (parsed < minimum || parsed > maximum) throw invalid(field + " is outside its range.");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be a whole number.");
        }
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        if (value == null) throw invalid(field + " is required.");
        var normalized = value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String upperCode(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static String oneOf(String value, String field, String... options) {
        for (var option : options) if (option.equals(value)) return value;
        throw invalid(field + " contains an unsupported value.");
    }

    private static String reportKey(String value) {
        if (!REPORT_KEYS.contains(value)) throw invalid("reportKey contains an unsupported value.");
        return value;
    }

    private static Instant nextRun(Instant now, String cadence) {
        return switch (cadence) {
            case "daily" -> now.plus(1, ChronoUnit.DAYS);
            case "weekly" -> now.plus(7, ChronoUnit.DAYS);
            case "monthly" -> now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            default -> throw invalid("The report cadence is unsupported.");
        };
    }

    private static String sha256(Object... values) {
        try {
            var canonical = new StringBuilder("m12-reporting-evidence-v1|");
            for (var value : values) {
                var text = Objects.toString(value, "<null>");
                canonical.append(text.length()).append(':').append(text).append(';');
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate reporting evidence digest.", exception);
        }
    }

    private static void requireRevision(long actual, Long expected, String target) {
        if (expected == null) {
            throw new ReportingException(
                    ReportingException.Reason.PRECONDITION_REQUIRED,
                    "A strong " + target + " revision is required.");
        }
        if (actual != expected) {
            throw new ReportingException(
                    ReportingException.Reason.STALE,
                    "The " + target + " changed; reload before continuing.");
        }
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw conflict(message);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return Map.copyOf(values);
    }

    private static ReportingScreen.Column column(String key, String label) {
        return new ReportingScreen.Column(key, label);
    }

    private static ReportingScreen.Metric metric(String key, String label, long value, String tone) {
        return new ReportingScreen.Metric(key, label, value, tone);
    }

    private static ReportingScreen.Notice notice(String tone, String title, String detail) {
        return new ReportingScreen.Notice(tone, title, detail);
    }

    private static String label(String key) {
        var text = key.replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String instantText(Object value) {
        return value == null ? "Not recorded" : value.toString();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static ReportingException invalid(String message) {
        return new ReportingException(ReportingException.Reason.INVALID, message);
    }

    private static ReportingException notFound(String message) {
        return new ReportingException(ReportingException.Reason.NOT_FOUND, message);
    }

    private static ReportingException conflict(String message) {
        return new ReportingException(ReportingException.Reason.CONFLICT, message);
    }

    private record ScheduleRecord(
            UUID id, String cadence, Instant nextRunAt, String status, long revision) {}

    private record RunRecord(
            UUID id,
            String reportKey,
            String purpose,
            String snapshotDigest,
            String status,
            long revision) {}
}
