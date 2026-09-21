package com.rootopathy.careos.workforce.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimum-necessary, server-authorized projection used by the twenty-nine Module 2 screens.
 * Authority, lifecycle truth and action availability are always projected by the server.
 */
public record WorkforceScreen(
        UUID organizationId,
        String screenId,
        String title,
        String purpose,
        Instant generatedAt,
        List<Metric> metrics,
        List<Column> columns,
        List<Row> rows,
        List<Action> actions,
        List<Notice> notices) {

    public WorkforceScreen {
        Objects.requireNonNull(organizationId, "organizationId");
        screenId = requireText(screenId, "screenId");
        title = requireText(title, "title");
        purpose = requireText(purpose, "purpose");
        Objects.requireNonNull(generatedAt, "generatedAt");
        metrics = List.copyOf(metrics == null ? List.of() : metrics);
        columns = List.copyOf(columns == null ? List.of() : columns);
        rows = List.copyOf(rows == null ? List.of() : rows);
        actions = List.copyOf(actions == null ? List.of() : actions);
        notices = List.copyOf(notices == null ? List.of() : notices);
    }

    public record Metric(String key, String label, long value, String tone) {
        public Metric {
            key = requireText(key, "metric key");
            label = requireText(label, "metric label");
            tone = requireText(tone, "metric tone");
        }
    }

    public record Column(String key, String label) {
        public Column {
            key = requireText(key, "column key");
            label = requireText(label, "column label");
        }
    }

    public record Row(
            UUID id,
            UUID memberId,
            String status,
            long revision,
            String etag,
            Map<String, String> values) {
        public Row {
            Objects.requireNonNull(id, "row id");
            status = requireText(status, "row status");
            if (revision < 0) {
                throw new IllegalArgumentException("row revision must not be negative");
            }
            etag = requireText(etag, "row etag");
            values = Map.copyOf(values == null ? Map.of() : values);
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
            key = requireText(key, "action key");
            label = requireText(label, "action label");
            style = requireText(style, "action style");
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
            key = requireText(key, "field key");
            label = requireText(label, "field label");
            inputType = requireText(inputType, "field inputType");
            options = List.copyOf(options == null ? List.of() : options);
        }
    }

    public record Option(String value, String label) {
        public Option {
            value = requireText(value, "option value");
            label = requireText(label, "option label");
        }
    }

    public record Notice(String tone, String title, String detail) {
        public Notice {
            tone = requireText(tone, "notice tone");
            title = requireText(title, "notice title");
            detail = requireText(detail, "notice detail");
        }
    }

    private static String requireText(String value, String name) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }
}
