-- Operational evidence needed by the approved Module 2 expiry and worker policies.
-- The 44-table baseline is unchanged; these columns make registration reminders and
-- failed offboarding reconciliation explicit rather than encoding them in free text.

ALTER TABLE workforce_notification_deliveries
    ADD COLUMN professional_registration_id uuid,
    ADD CONSTRAINT workforce_notifications_registration_fk
        FOREIGN KEY (organization_id,professional_registration_id)
        REFERENCES professional_registrations(organization_id,id),
    ADD CONSTRAINT workforce_notifications_one_expiry_source_check
        CHECK (NOT (
            practitioner_credential_id IS NOT NULL
            AND professional_registration_id IS NOT NULL
        ));

CREATE UNIQUE INDEX workforce_notifications_registration_attempt_uq
    ON workforce_notification_deliveries(
        organization_id,professional_registration_id,milestone,template_version_id,attempt_number
    )
    WHERE professional_registration_id IS NOT NULL;

CREATE INDEX workforce_notifications_planned_idx
    ON workforce_notification_deliveries(organization_id,created_at,id)
    WHERE status='planned';

ALTER TABLE workforce_offboarding_requests
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN dead_lettered_at timestamptz,
    ADD CONSTRAINT workforce_offboarding_attempt_count_check
        CHECK (attempt_count BETWEEN 0 AND 5),
    ADD CONSTRAINT workforce_offboarding_failure_shape_check
        CHECK (
            (status='failed' AND failure_code IS NOT NULL)
            OR (status<>'failed' AND failure_code IS NULL)
        );

CREATE INDEX workforce_offboarding_reconciliation_idx
    ON workforce_offboarding_requests(organization_id,next_attempt_at,id)
    WHERE status='failed' AND dead_lettered_at IS NULL;
