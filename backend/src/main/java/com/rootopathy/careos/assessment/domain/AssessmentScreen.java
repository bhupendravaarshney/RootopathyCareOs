package com.rootopathy.careos.assessment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Minimum-necessary, server-authorized projection for COS-01 through COS-27. */
public record AssessmentScreen(
        UUID organizationId,
        String screenId,
        String title,
        String purpose,
        Instant generatedAt,
        List<Metric> metrics,
        List<Column> columns,
        List<Row> rows,
        List<Action> actions,
        List<Notice> notices,
        String nextCursor,
        int pageSize) {

    public AssessmentScreen {
        Objects.requireNonNull(organizationId, "organizationId");
        screenId = text(screenId, "screenId");
        title = text(title, "title");
        purpose = text(purpose, "purpose");
        Objects.requireNonNull(generatedAt, "generatedAt");
        metrics = List.copyOf(metrics == null ? List.of() : metrics);
        columns = List.copyOf(columns == null ? List.of() : columns);
        rows = List.copyOf(rows == null ? List.of() : rows);
        actions = List.copyOf(actions == null ? List.of() : actions);
        notices = List.copyOf(notices == null ? List.of() : notices);
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be null or non-empty");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
    }

    public record Metric(String key, String label, long value, String tone) {
        public Metric {
            key = text(key, "metric key");
            label = text(label, "metric label");
            tone = text(tone, "metric tone");
        }
    }

    public record Column(String key, String label) {
        public Column {
            key = text(key, "column key");
            label = text(label, "column label");
        }
    }

    public record Row(
            UUID id,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            String status,
            long revision,
            String etag,
            Map<String, String> values,
            List<String> allowedActionKeys) {
        public Row {
            Objects.requireNonNull(id, "row id");
            status = text(status, "row status");
            if (revision < 0) throw new IllegalArgumentException("row revision must not be negative");
            etag = text(etag, "row etag");
            values = Map.copyOf(values == null ? Map.of() : values);
            allowedActionKeys = List.copyOf(
                    allowedActionKeys == null ? List.of() : allowedActionKeys);
        }
    }

    public record Action(
            String key,
            String label,
            String style,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            String href,
            List<Field> fields) {
        public Action {
            key = text(key, "action key");
            label = text(label, "action label");
            style = text(style, "action style");
            fields = List.copyOf(fields == null ? List.of() : fields);
        }
    }

    public record Field(
            String key,
            String label,
            String inputType,
            boolean required,
            String help,
            List<Option> options) {
        public Field {
            key = text(key, "field key");
            label = text(label, "field label");
            inputType = text(inputType, "field input type");
            options = List.copyOf(options == null ? List.of() : options);
        }
    }

    public record Option(String value, String label) {
        public Option {
            value = text(value, "option value");
            label = text(label, "option label");
        }
    }

    public record Notice(String tone, String title, String detail) {
        public Notice {
            tone = text(tone, "notice tone");
            title = text(title, "notice title");
            detail = text(detail, "notice detail");
        }
    }

    private static String text(String value, String name) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
