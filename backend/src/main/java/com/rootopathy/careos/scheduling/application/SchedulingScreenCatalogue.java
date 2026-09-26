package com.rootopathy.careos.scheduling.application;

import com.rootopathy.careos.scheduling.domain.SchedulingScreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SchedulingScreenCatalogue {
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private SchedulingScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new SchedulingException(
                    SchedulingException.Reason.NOT_FOUND,
                    "The requested scheduling screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.href() == null && action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new SchedulingException(
                        SchedulingException.Reason.NOT_FOUND,
                        "The requested scheduling action does not exist."));
    }

    static List<SchedulingScreen.Action> projectedActions(
            ScreenSpec screen, Set<String> permissions) {
        return screen.actions().stream()
                .filter(action -> action.href() != null || permissions.contains(action.permission()))
                .map(ActionSpec::projection)
                .toList();
    }

    record ScreenSpec(
            String id,
            String title,
            String purpose,
            String readOperation,
            List<ActionSpec> actions) {}

    record ActionSpec(
            String key,
            String label,
            String operation,
            String permission,
            String style,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            String href,
            List<SchedulingScreen.Field> fields) {
        SchedulingScreen.Action projection() {
            return new SchedulingScreen.Action(
                    key,
                    label,
                    style,
                    targetRequired,
                    ifMatchRequired,
                    reasonRequired,
                    href,
                    fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(
                screens,
                "P4-01",
                "Scheduling dashboard",
                "Review appointment activity, expiring holds and waitlist work.",
                "appointment.dashboard.read",
                link("new-appointment", "New appointment", "#/P4-04"),
                link("open-calendar", "Open calendar", "#/P4-02"),
                link("open-waitlist", "Open waitlist", "#/P4-14"));
        add(
                screens,
                "P4-02",
                "Calendar",
                "Manage internal schedules and exact UTC appointment slots.",
                "appointment.schedule.read",
                action(
                        "create-schedule",
                        "Create schedule",
                        "appointment.schedule.manage",
                        false,
                        false,
                        true,
                        uuid("serviceId", "Service", true),
                        uuid("facilityId", "Facility", true),
                        uuid("locationId", "Location", true),
                        uuid("practitionerId", "Practitioner", true),
                        text("timezone", "IANA timezone", true),
                        dateTime("effectiveFrom", "Effective from", true),
                        dateTime("effectiveTo", "Effective to", true),
                        number("slotDurationMinutes", "Slot duration minutes", true)),
                action(
                        "activate-schedule",
                        "Activate schedule",
                        "appointment.schedule.manage",
                        true,
                        true,
                        true),
                action(
                        "create-slot",
                        "Create slot",
                        "appointment.schedule.manage",
                        true,
                        true,
                        true,
                        dateTime("startsAt", "Starts at", true),
                        dateTime("endsAt", "Ends at", true)));
        add(
                screens,
                "P4-03",
                "Appointment directory",
                "Search bounded minimum-necessary appointment records.",
                "appointment.directory.read",
                link("new-appointment", "New appointment", "#/P4-04"));
        add(
                screens,
                "P4-04",
                "New appointment",
                "Start a staff-authorized expiring appointment request.",
                "appointment.request.manage",
                action(
                        "start-appointment-request",
                        "Start request",
                        "appointment.request.manage",
                        false,
                        false,
                        false,
                        uuid("patientId", "Patient", true),
                        select("requestSource", "Request source", true, "staff", "Staff", "referral", "Referral"),
                        text("purposeKey", "Purpose", true)));
        add(
                screens,
                "P4-05",
                "Patient selection",
                "Review or replace the selected patient before a slot is held.",
                "appointment.request.manage",
                action(
                        "select-request-patient",
                        "Select patient",
                        "appointment.request.manage",
                        true,
                        true,
                        false,
                        uuid("patientId", "Patient", true)));
        add(
                screens,
                "P4-06",
                "Service, facility and location",
                "Select an active service context for the appointment request.",
                "appointment.request.manage",
                action(
                        "set-request-context",
                        "Set care context",
                        "appointment.request.manage",
                        true,
                        true,
                        false,
                        uuid("serviceId", "Service", true),
                        uuid("facilityId", "Facility", true),
                        uuid("locationId", "Location", true)));
        add(
                screens,
                "P4-07",
                "Eligible clinician selection",
                "Select a practitioner; eligibility is re-evaluated for the final slot instant.",
                "appointment.request.manage",
                action(
                        "select-request-practitioner",
                        "Select practitioner",
                        "appointment.request.manage",
                        true,
                        true,
                        false,
                        uuid("practitionerId", "Practitioner", true)));
        add(
                screens,
                "P4-08",
                "Slot selection",
                "Atomically acquire a five-minute internal slot hold.",
                "appointment.schedule.read",
                action(
                        "hold-slot",
                        "Hold slot",
                        "appointment.slot.hold",
                        true,
                        true,
                        false,
                        uuid("slotId", "Slot", true)));
        add(
                screens,
                "P4-09",
                "Appointment review",
                "Review the exact patient, service, clinician, slot and hold expiry.",
                "appointment.request.manage");
        add(
                screens,
                "P4-10",
                "Payment requirement",
                "Review non-financial payment state; charging is deferred to Module 11.",
                "appointment.payment.read");
        add(
                screens,
                "P4-11",
                "Confirmation",
                "Re-evaluate eligibility and consume the held slot atomically.",
                "appointment.request.manage",
                action(
                        "confirm-appointment",
                        "Confirm appointment",
                        "appointment.book",
                        true,
                        true,
                        true));
        add(
                screens,
                "P4-12",
                "Reschedule",
                "Move a confirmed appointment while retaining immutable prior-slot evidence.",
                "appointment.directory.read",
                action(
                        "reschedule-appointment",
                        "Reschedule appointment",
                        "appointment.reschedule",
                        true,
                        true,
                        true,
                        uuid("newSlotId", "New slot", true)));
        add(
                screens,
                "P4-13",
                "Cancel or no-show",
                "Record an operational outcome without inventing a fee or refund decision.",
                "appointment.directory.read",
                action(
                        "cancel-appointment",
                        "Cancel appointment",
                        "appointment.cancel",
                        true,
                        true,
                        true,
                        text("reasonCode", "Reason code", true)),
                action(
                        "record-no-show",
                        "Record no-show",
                        "appointment.no_show",
                        true,
                        true,
                        true,
                        text("reasonCode", "Reason code", true),
                        text("evidenceCode", "Evidence code", true)));
        add(
                screens,
                "P4-14",
                "Waitlist",
                "Manage internal waitlist requests without sending unapproved offers.",
                "appointment.waitlist.read",
                action(
                        "join-waitlist",
                        "Join waitlist",
                        "appointment.waitlist.manage",
                        false,
                        false,
                        true,
                        uuid("patientId", "Patient", true),
                        uuid("serviceId", "Service", true),
                        uuid("facilityId", "Facility", false),
                        uuid("locationId", "Location", false),
                        dateTime("earliestAt", "Earliest time", true),
                        dateTime("latestAt", "Latest time", true),
                        select("priorityKey", "Priority", true, "standard", "Standard", "urgent_review", "Urgent review")),
                action(
                        "withdraw-waitlist",
                        "Withdraw entry",
                        "appointment.waitlist.manage",
                        true,
                        true,
                        true));
        add(
                screens,
                "P4-15",
                "Appointment timeline",
                "Review allow-listed lifecycle evidence without raw audit/provider payloads.",
                "appointment.timeline.read");
        return Map.copyOf(screens);
    }

    private static void add(
            Map<String, ScreenSpec> screens,
            String id,
            String title,
            String purpose,
            String readOperation,
            ActionSpec... actions) {
        screens.put(id, new ScreenSpec(id, title, purpose, readOperation, List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            SchedulingScreen.Field... fields) {
        return new ActionSpec(
                key,
                label,
                operation,
                operation,
                "primary",
                targetRequired,
                ifMatchRequired,
                reasonRequired,
                null,
                List.of(fields));
    }

    private static ActionSpec link(String key, String label, String href) {
        return new ActionSpec(key, label, "", "", "link", false, false, false, href, List.of());
    }

    private static SchedulingScreen.Field text(String key, String label, boolean required) {
        return field(key, label, "text", required, List.of());
    }

    private static SchedulingScreen.Field uuid(String key, String label, boolean required) {
        return field(key, label, "uuid", required, List.of());
    }

    private static SchedulingScreen.Field dateTime(String key, String label, boolean required) {
        return field(key, label, "datetime-local", required, List.of());
    }

    private static SchedulingScreen.Field number(String key, String label, boolean required) {
        return field(key, label, "number", required, List.of());
    }

    private static SchedulingScreen.Field select(
            String key, String label, boolean required, String... valueLabels) {
        var options = new java.util.ArrayList<SchedulingScreen.Option>();
        for (var index = 0; index < valueLabels.length; index += 2) {
            options.add(new SchedulingScreen.Option(valueLabels[index], valueLabels[index + 1]));
        }
        return field(key, label, "select", required, options);
    }

    private static SchedulingScreen.Field field(
            String key,
            String label,
            String inputType,
            boolean required,
            List<SchedulingScreen.Option> options) {
        return new SchedulingScreen.Field(key, label, inputType, required, null, options);
    }
}
