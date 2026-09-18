DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM authorization_registry_releases
        WHERE registry_version = 'm1-candidate-1'
          AND approval_record_id = 'M1-APPROVAL-20260916-01'
          AND approval_package_sha256 =
              '19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946'
          AND status = 'active'
    ) THEN
        RAISE EXCEPTION
            'V21 requires the active checksum-approved Module 1 authorization release';
    END IF;
END;
$$;

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
VALUES
    ('access.membership.read', 'access.membership.read', 'Read organization access',
     'Read a minimum-necessary cursor page of organization memberships and live actions.',
     false, 'hidden', false, false, NULL, NULL, false, 'active', 'm1-candidate-1', false);

CREATE INDEX organization_memberships_org_effective_page_idx
    ON organization_memberships (organization_id, effective_from DESC, id DESC);

COMMENT ON INDEX organization_memberships_org_effective_page_idx IS
    'Stable keyset order for the approved M1-20 membership read projection.';
