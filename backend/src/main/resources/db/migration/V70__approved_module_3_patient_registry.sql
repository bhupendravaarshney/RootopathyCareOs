CREATE TABLE patient_profiles (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_number varchar(48) NOT NULL,
    lifecycle_state varchar(32) NOT NULL DEFAULT 'draft',
    official_given_name varchar(120),
    official_family_name varchar(120),
    name_to_use varchar(160),
    name_state varchar(32) NOT NULL DEFAULT 'provided',
    birth_date_value date,
    birth_date_precision varchar(16),
    birth_date_certainty varchar(16) NOT NULL DEFAULT 'unknown',
    administrative_sex_code varchar(80),
    gender_identity_code varchar(80),
    pronouns_code varchar(80),
    deceased_state varchar(24) NOT NULL DEFAULT 'not_recorded',
    deceased_date_value date,
    deceased_date_precision varchar(16),
    merged_into_patient_id uuid,
    temporary_identity boolean NOT NULL DEFAULT false,
    temporary_reason_code varchar(80),
    provenance_source varchar(80) NOT NULL,
    verification_state varchar(40) NOT NULL DEFAULT 'self_or_source_attested',
    classification varchar(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, patient_number),
    FOREIGN KEY (organization_id, merged_into_patient_id)
        REFERENCES patient_profiles(organization_id, id),
    CHECK (patient_number ~ '^[A-Z0-9][A-Z0-9_-]{2,47}$'),
    CHECK (lifecycle_state IN
        ('draft','pending_review','active','inactive','deceased','merged','entered_in_error')),
    CHECK (name_state IN ('provided','temporary','unnamed','unknown')),
    CHECK (official_given_name IS NULL OR char_length(btrim(official_given_name)) BETWEEN 1 AND 120),
    CHECK (official_family_name IS NULL OR char_length(btrim(official_family_name)) BETWEEN 1 AND 120),
    CHECK (name_to_use IS NULL OR char_length(btrim(name_to_use)) BETWEEN 1 AND 160),
    CHECK (birth_date_precision IS NULL OR birth_date_precision IN ('year','month','day')),
    CHECK (birth_date_certainty IN ('exact','estimated','unknown')),
    CHECK ((birth_date_certainty = 'unknown') = (birth_date_value IS NULL)),
    CHECK ((birth_date_value IS NULL) = (birth_date_precision IS NULL)),
    CHECK (birth_date_value IS NULL OR birth_date_value <= CURRENT_DATE),
    CHECK (deceased_state IN ('not_recorded','alive','deceased','unknown')),
    CHECK (deceased_date_precision IS NULL OR deceased_date_precision IN ('year','month','day')),
    CHECK ((deceased_date_value IS NULL) = (deceased_date_precision IS NULL)),
    CHECK ((lifecycle_state = 'merged') = (merged_into_patient_id IS NOT NULL)),
    CHECK (merged_into_patient_id IS NULL OR merged_into_patient_id <> id),
    CHECK (temporary_identity OR temporary_reason_code IS NULL),
    CHECK (verification_state IN
        ('unverified','self_or_source_attested','evidence_checked','authoritative_source_verified')),
    CHECK (classification IN ('INTERNAL','CONFIDENTIAL','RESTRICTED')),
    CHECK (lock_version >= 0),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at)
);

CREATE TABLE patient_identifiers (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    scheme_key varchar(80) NOT NULL,
    scheme_version varchar(80) NOT NULL,
    identifier_type varchar(80) NOT NULL,
    issuer_key varchar(120) NOT NULL,
    jurisdiction_code varchar(80),
    encrypted_value bytea NOT NULL,
    lookup_key_version varchar(80) NOT NULL,
    value_digest char(64) NOT NULL,
    masked_display varchar(80) NOT NULL,
    verification_state varchar(32) NOT NULL DEFAULT 'unverified',
    primary_identifier boolean NOT NULL DEFAULT false,
    issued_on date,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'draft',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES patient_identifiers(organization_id, id),
    CHECK (scheme_key ~ '^[a-z][a-z0-9_.-]{1,79}$'),
    CHECK (value_digest ~ '^[0-9a-f]{64}$'),
    CHECK (char_length(masked_display) BETWEEN 2 AND 80),
    CHECK (verification_state IN ('unverified','pending','verified','failed')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('draft','active','ended','superseded','revoked','entered_in_error')),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX patient_identifiers_active_value_uq
    ON patient_identifiers (organization_id, scheme_key, scheme_version, value_digest)
    WHERE status = 'active' AND effective_to IS NULL;
CREATE UNIQUE INDEX patient_identifiers_primary_uq
    ON patient_identifiers (organization_id, patient_id, scheme_key)
    WHERE primary_identifier AND status = 'active' AND effective_to IS NULL;

CREATE TABLE patient_contacts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    channel varchar(24) NOT NULL,
    contact_use varchar(32) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    encrypted_value bytea NOT NULL,
    normalization_version varchar(80) NOT NULL,
    value_digest char(64) NOT NULL,
    masked_display varchar(160) NOT NULL,
    verification_state varchar(32) NOT NULL DEFAULT 'unverified',
    verification_method varchar(80),
    verified_at timestamptz,
    primary_contact boolean NOT NULL DEFAULT false,
    preferred_contact boolean NOT NULL DEFAULT false,
    confidential boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'active',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES patient_contacts(organization_id, id),
    CHECK (channel IN ('email','phone','sms','other')),
    CHECK (value_digest ~ '^[0-9a-f]{64}$'),
    CHECK (char_length(masked_display) BETWEEN 3 AND 160),
    CHECK (verification_state IN ('unverified','pending','verified','failed')),
    CHECK ((verification_state = 'verified') = (verified_at IS NOT NULL)),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded','entered_in_error')),
    CHECK (classification IN ('CONFIDENTIAL','RESTRICTED')),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX patient_contacts_primary_uq
    ON patient_contacts (organization_id, patient_id, channel, purpose_key)
    WHERE primary_contact AND status = 'active' AND effective_to IS NULL;

CREATE TABLE patient_addresses (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    address_use varchar(32) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    encrypted_payload bytea NOT NULL,
    normalized_digest char(64) NOT NULL,
    masked_summary varchar(200) NOT NULL,
    country_code char(2) NOT NULL,
    validation_state varchar(32) NOT NULL DEFAULT 'unvalidated',
    validation_source varchar(80),
    primary_address boolean NOT NULL DEFAULT false,
    preferred_address boolean NOT NULL DEFAULT false,
    confidential boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'active',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES patient_addresses(organization_id, id),
    CHECK (normalized_digest ~ '^[0-9a-f]{64}$'),
    CHECK (char_length(masked_summary) BETWEEN 3 AND 200),
    CHECK (country_code ~ '^[A-Z]{2}$'),
    CHECK (validation_state IN ('unvalidated','valid','invalid','manual_review','unavailable')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded','entered_in_error')),
    CHECK (classification IN ('CONFIDENTIAL','RESTRICTED')),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX patient_addresses_primary_uq
    ON patient_addresses (organization_id, patient_id, address_use, purpose_key)
    WHERE primary_address AND status = 'active' AND effective_to IS NULL;

CREATE TABLE communication_preferences (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    purpose_key varchar(80) NOT NULL,
    channel varchar(24) NOT NULL,
    decision varchar(16) NOT NULL,
    language_tag varchar(35),
    accessible_format_key varchar(80),
    quiet_start time,
    quiet_end time,
    timezone varchar(80),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    CHECK (channel IN ('email','phone','sms','postal','portal','other')),
    CHECK (decision IN ('allow','deny','prefer')),
    CHECK (language_tag IS NULL OR language_tag ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CHECK ((quiet_start IS NULL) = (quiet_end IS NULL)),
    CHECK ((quiet_start IS NULL) = (timezone IS NULL)),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded','entered_in_error')),
    CHECK (classification = 'CONFIDENTIAL'),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX communication_preferences_active_uq
    ON communication_preferences (organization_id, patient_id, purpose_key, channel)
    WHERE status = 'active' AND effective_to IS NULL;

CREATE TABLE caregiver_relationships (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    related_person_reference uuid NOT NULL,
    related_patient_id uuid,
    relationship_type_key varchar(80) NOT NULL,
    display_label varchar(160) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, related_patient_id) REFERENCES patient_profiles(organization_id, id),
    CHECK (related_patient_id IS NULL OR related_patient_id <> patient_id),
    CHECK (char_length(btrim(display_label)) BETWEEN 1 AND 160),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded','entered_in_error')),
    CHECK (classification IN ('CONFIDENTIAL','RESTRICTED')),
    CHECK (lock_version >= 0)
);

CREATE TABLE patient_authority_grants (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    caregiver_relationship_id uuid NOT NULL,
    grantor_reference uuid NOT NULL,
    grantee_reference uuid NOT NULL,
    authority_type_key varchar(80) NOT NULL,
    authority_source_key varchar(80) NOT NULL,
    jurisdiction_code varchar(80) NOT NULL,
    scope_digest char(64) NOT NULL,
    purpose_keys varchar(80)[] NOT NULL,
    action_keys varchar(80)[] NOT NULL,
    data_class_keys varchar(80)[] NOT NULL,
    evidence_reference uuid NOT NULL,
    verification_state varchar(32) NOT NULL DEFAULT 'pending_review',
    requested_by uuid NOT NULL,
    decided_by uuid,
    effective_from timestamptz,
    effective_to timestamptz,
    revoked_at timestamptz,
    status varchar(32) NOT NULL DEFAULT 'proposed',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, caregiver_relationship_id)
        REFERENCES caregiver_relationships(organization_id, id),
    CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    CHECK (cardinality(purpose_keys) BETWEEN 1 AND 32 AND array_position(purpose_keys, NULL) IS NULL),
    CHECK (cardinality(action_keys) BETWEEN 1 AND 32 AND array_position(action_keys, NULL) IS NULL),
    CHECK (cardinality(data_class_keys) BETWEEN 1 AND 32 AND array_position(data_class_keys, NULL) IS NULL),
    CHECK (verification_state IN ('pending_review','verified','rejected','unavailable')),
    CHECK (effective_to IS NULL OR (effective_from IS NOT NULL AND effective_to > effective_from)),
    CHECK (status IN ('proposed','pending_review','active','suspended','revoked','expired','superseded','rejected')),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'revoked') = (revoked_at IS NOT NULL)),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE TABLE patient_portal_links (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id),
    authority_grant_id uuid,
    link_type varchar(16) NOT NULL,
    proofing_policy_version varchar(80) NOT NULL,
    proofing_result_digest char(64) NOT NULL,
    requested_by uuid NOT NULL,
    decided_by uuid,
    effective_from timestamptz,
    effective_to timestamptz,
    revoked_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'requested',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, authority_grant_id)
        REFERENCES patient_authority_grants(organization_id, id),
    CHECK (link_type IN ('self','proxy')),
    CHECK ((link_type = 'proxy') = (authority_grant_id IS NOT NULL)),
    CHECK (proofing_result_digest ~ '^[0-9a-f]{64}$'),
    CHECK (effective_to IS NULL OR (effective_from IS NOT NULL AND effective_to > effective_from)),
    CHECK (status IN ('requested','pending_review','active','suspended','revoked','expired','rejected')),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'revoked') = (revoked_at IS NOT NULL)),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX patient_portal_links_active_uq
    ON patient_portal_links (organization_id, patient_id, user_id, link_type)
    WHERE status = 'active' AND effective_to IS NULL;

CREATE TABLE patient_consents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    grantor_reference uuid NOT NULL,
    authority_grant_id uuid,
    decision varchar(16) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    grantee_key varchar(120) NOT NULL,
    action_key varchar(80) NOT NULL,
    data_scope_digest char(64) NOT NULL,
    legal_basis_key varchar(80) NOT NULL,
    directive_reference uuid NOT NULL,
    directive_digest char(64) NOT NULL,
    derivative boolean NOT NULL DEFAULT false,
    verification_state varchar(32) NOT NULL DEFAULT 'pending',
    effective_from timestamptz NOT NULL,
    expires_at timestamptz,
    withdrawn_at timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'draft',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, authority_grant_id)
        REFERENCES patient_authority_grants(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES patient_consents(organization_id, id),
    CHECK (decision IN ('permit','deny')),
    CHECK (data_scope_digest ~ '^[0-9a-f]{64}$' AND directive_digest ~ '^[0-9a-f]{64}$'),
    CHECK (verification_state IN ('pending','verified','rejected','unavailable')),
    CHECK (expires_at IS NULL OR expires_at > effective_from),
    CHECK (withdrawn_at IS NULL OR withdrawn_at >= effective_from),
    CHECK (status IN ('draft','active','withdrawn','expired','superseded','entered_in_error')),
    CHECK ((status = 'withdrawn') = (withdrawn_at IS NOT NULL)),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE TABLE privacy_restrictions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    request_reference uuid NOT NULL,
    restriction_type_key varchar(80) NOT NULL,
    scope_digest char(64) NOT NULL,
    field_keys varchar(80)[] NOT NULL,
    resource_keys varchar(80)[] NOT NULL,
    purpose_keys varchar(80)[] NOT NULL,
    channel_keys varchar(80)[] NOT NULL,
    projection_consequence_key varchar(80) NOT NULL,
    reason_reference uuid NOT NULL,
    evidence_reference uuid,
    requested_by uuid NOT NULL,
    decided_by uuid,
    decision varchar(16),
    effective_from timestamptz,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'proposed',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES privacy_restrictions(organization_id, id),
    CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    CHECK (array_position(field_keys, NULL) IS NULL AND array_position(resource_keys, NULL) IS NULL),
    CHECK (array_position(purpose_keys, NULL) IS NULL AND array_position(channel_keys, NULL) IS NULL),
    CHECK (decision IS NULL OR decision IN ('accept','reject')),
    CHECK (effective_to IS NULL OR (effective_from IS NOT NULL AND effective_to > effective_from)),
    CHECK (status IN ('proposed','active','rejected','ended','superseded','entered_in_error')),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE TABLE patient_safety_flags (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    category_key varchar(80) NOT NULL,
    severity_key varchar(80) NOT NULL,
    statement_code varchar(80) NOT NULL,
    source_reference_type varchar(80) NOT NULL,
    source_reference_id uuid NOT NULL,
    visibility_key varchar(80) NOT NULL,
    author_id uuid NOT NULL,
    verifier_id uuid,
    verification_deadline timestamptz,
    effective_from timestamptz,
    review_at timestamptz,
    expires_at timestamptz,
    resolved_at timestamptz,
    successor_id uuid,
    status varchar(24) NOT NULL DEFAULT 'proposed',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, successor_id) REFERENCES patient_safety_flags(organization_id, id),
    CHECK (verifier_id IS NULL OR verifier_id <> author_id),
    CHECK (review_at IS NULL OR effective_from IS NULL OR review_at > effective_from),
    CHECK (expires_at IS NULL OR effective_from IS NULL OR expires_at > effective_from),
    CHECK (status IN ('proposed','provisional','active','resolved','superseded','entered_in_error')),
    CHECK ((status = 'active') = (verifier_id IS NOT NULL)),
    CHECK ((status = 'resolved') = (resolved_at IS NOT NULL)),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE TABLE patient_match_keys (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    factor_type varchar(80) NOT NULL,
    normalization_version varchar(80) NOT NULL,
    detector_version varchar(80) NOT NULL,
    hmac_key_version varchar(80) NOT NULL,
    keyed_digest char(64) NOT NULL,
    blocking_bucket char(64),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, patient_id, factor_type, hmac_key_version, keyed_digest),
    FOREIGN KEY (organization_id, patient_id) REFERENCES patient_profiles(organization_id, id),
    CHECK (keyed_digest ~ '^[0-9a-f]{64}$'),
    CHECK (blocking_bucket IS NULL OR blocking_bucket ~ '^[0-9a-f]{64}$'),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','rotated','revoked')),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE TABLE patient_duplicate_candidates (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_a_id uuid NOT NULL,
    patient_b_id uuid NOT NULL,
    patient_a_revision bigint NOT NULL,
    patient_b_revision bigint NOT NULL,
    detector_version varchar(80) NOT NULL,
    factor_categories varchar(80)[] NOT NULL,
    result_digest char(64) NOT NULL,
    match_band varchar(40) NOT NULL,
    calibrated_score numeric(7,6),
    priority_key varchar(40) NOT NULL DEFAULT 'routine',
    review_due_at timestamptz NOT NULL,
    assigned_reviewer_id uuid,
    lease_reference uuid,
    lease_expires_at timestamptz,
    disposition varchar(40),
    disposition_reason_code varchar(80),
    dismissed_snapshot_digest char(64),
    status varchar(24) NOT NULL DEFAULT 'open',
    provenance_source varchar(80) NOT NULL DEFAULT 'm3-match-refresh-v1',
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, patient_a_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, patient_b_id) REFERENCES patient_profiles(organization_id, id),
    CHECK (patient_a_id < patient_b_id),
    CHECK (patient_a_revision >= 0 AND patient_b_revision >= 0),
    CHECK (cardinality(factor_categories) BETWEEN 1 AND 16 AND array_position(factor_categories, NULL) IS NULL),
    CHECK (result_digest ~ '^[0-9a-f]{64}$'),
    CHECK (match_band IN
        ('confirmed_identifier_conflict','high_review','possible_review','below_display_threshold')),
    CHECK (calibrated_score IS NULL OR calibrated_score BETWEEN 0 AND 1),
    CHECK (priority_key IN ('urgent','high','routine','low')),
    CHECK ((assigned_reviewer_id IS NULL) = (lease_reference IS NULL)),
    CHECK ((lease_reference IS NULL) = (lease_expires_at IS NULL)),
    CHECK (disposition IS NULL OR disposition IN
        ('not_duplicate','merge_requested','same_patient_no_merge','insufficient_evidence')),
    CHECK (dismissed_snapshot_digest IS NULL OR dismissed_snapshot_digest ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('open','under_review','dismissed','merge_requested','resolved')),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX patient_duplicate_candidates_active_pair_uq
    ON patient_duplicate_candidates (organization_id, patient_a_id, patient_b_id)
    WHERE status IN ('open','under_review','merge_requested');

CREATE TABLE patient_merge_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    duplicate_candidate_id uuid,
    survivor_patient_id uuid NOT NULL,
    duplicate_patient_id uuid NOT NULL,
    survivor_revision bigint NOT NULL,
    duplicate_revision bigint NOT NULL,
    field_disposition_digest char(64) NOT NULL,
    reference_inventory_digest char(64) NOT NULL,
    impact_digest char(64) NOT NULL,
    affected_reference_count integer NOT NULL,
    reason_code varchar(80) NOT NULL,
    requested_by uuid NOT NULL,
    submitted_at timestamptz,
    expires_at timestamptz NOT NULL,
    executed_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'draft',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, duplicate_candidate_id)
        REFERENCES patient_duplicate_candidates(organization_id, id),
    FOREIGN KEY (organization_id, survivor_patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, duplicate_patient_id) REFERENCES patient_profiles(organization_id, id),
    CHECK (survivor_patient_id <> duplicate_patient_id),
    CHECK (survivor_revision >= 0 AND duplicate_revision >= 0),
    CHECK (field_disposition_digest ~ '^[0-9a-f]{64}$'),
    CHECK (reference_inventory_digest ~ '^[0-9a-f]{64}$'),
    CHECK (impact_digest ~ '^[0-9a-f]{64}$'),
    CHECK (affected_reference_count >= 0),
    CHECK (expires_at > created_at),
    CHECK (status IN ('draft','submitted','approved','rejected','expired','executed','correction_opened')),
    CHECK ((status = 'executed') = (executed_at IS NOT NULL)),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX patient_merge_requests_active_duplicate_uq
    ON patient_merge_requests (organization_id, duplicate_patient_id)
    WHERE status IN ('draft','submitted','approved');

CREATE TABLE patient_merge_decisions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    merge_request_id uuid NOT NULL,
    request_revision bigint NOT NULL,
    impact_digest char(64) NOT NULL,
    decision varchar(16) NOT NULL,
    reason_code varchar(80) NOT NULL,
    checker_id uuid NOT NULL,
    assurance_reference uuid NOT NULL,
    decision_expires_at timestamptz NOT NULL,
    consumed_by uuid,
    consumed_at timestamptz,
    correction_reference uuid,
    status varchar(24) NOT NULL,
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, merge_request_id, request_revision),
    FOREIGN KEY (organization_id, merge_request_id) REFERENCES patient_merge_requests(organization_id, id),
    CHECK (request_revision >= 0),
    CHECK (impact_digest ~ '^[0-9a-f]{64}$'),
    CHECK (decision IN ('approve','reject')),
    CHECK (decision_expires_at > created_at),
    CHECK ((consumed_by IS NULL) = (consumed_at IS NULL)),
    CHECK (status IN ('approved','rejected','consumed','expired','correction_opened')),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version = 0)
);

CREATE TABLE patient_registration_runs (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    registration_source varchar(80) NOT NULL,
    supplier_relationship_key varchar(80),
    purpose_key varchar(80) NOT NULL,
    facility_id uuid,
    creator_id uuid NOT NULL,
    current_step varchar(40) NOT NULL DEFAULT 'search',
    staged_payload bytea,
    staged_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    duplicate_search_completed_at timestamptz,
    duplicate_detector_version varchar(80),
    duplicate_result_digest char(64),
    duplicate_disposition varchar(40),
    selected_patient_id uuid,
    validation_digest char(64),
    validation_completed_at timestamptz,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '24 hours'),
    completed_patient_id uuid,
    completed_at timestamptz,
    urgent boolean NOT NULL DEFAULT false,
    urgent_reason_code varchar(80),
    status varchar(32) NOT NULL DEFAULT 'collecting',
    provenance_source varchar(80) NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'RESTRICTED',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, facility_id) REFERENCES facilities(organization_id, id),
    FOREIGN KEY (organization_id, selected_patient_id) REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, completed_patient_id) REFERENCES patient_profiles(organization_id, id),
    CHECK (current_step IN
        ('search','identity','contacts','preferences','identifiers','authority','consent_privacy','safety','review','complete')),
    CHECK (jsonb_typeof(staged_summary) = 'object'),
    CHECK (duplicate_result_digest IS NULL OR duplicate_result_digest ~ '^[0-9a-f]{64}$'),
    CHECK (duplicate_disposition IS NULL OR duplicate_disposition IN
        ('create_new','use_existing','escalate_review','urgent_temporary')),
    CHECK (validation_digest IS NULL OR validation_digest ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at > created_at),
    CHECK (urgent OR urgent_reason_code IS NULL),
    CHECK (status IN
        ('collecting','duplicate_review','ready','submitted','completed','abandoned','rejected','expired')),
    CHECK ((status = 'completed') = (completed_patient_id IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK (classification = 'RESTRICTED'),
    CHECK (lock_version >= 0)
);

CREATE INDEX patient_profiles_directory_idx
    ON patient_profiles (organization_id, lifecycle_state, updated_at DESC, id);
CREATE INDEX patient_profiles_name_idx
    ON patient_profiles (organization_id, lower(official_family_name), lower(official_given_name));
CREATE INDEX patient_registration_runs_work_idx
    ON patient_registration_runs (organization_id, status, updated_at DESC, id);
CREATE INDEX patient_duplicate_candidates_queue_idx
    ON patient_duplicate_candidates (organization_id, status, priority_key, review_due_at, id);
CREATE INDEX patient_safety_flags_patient_idx
    ON patient_safety_flags (organization_id, patient_id, status, updated_at DESC);

CREATE FUNCTION careos_validate_m3_tenant_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_organization uuid := nullif(current_setting('app.current_organization_id', true), '')::uuid;
    configured_actor uuid := nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text := nullif(current_setting('app.current_operation_key', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL
       OR configured_operation IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM authorization_operations operation
           JOIN authorization_registry_releases release
             ON release.registry_version = operation.registry_version
            AND release.status = 'active'
           WHERE operation.operation_key = configured_operation
             AND operation.registry_version = 'm3-candidate-1'
             AND operation.status = 'active'
       ) THEN
        RAISE EXCEPTION 'invalid Module 3 tenant write context' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> 0 THEN
            RAISE EXCEPTION 'invalid Module 3 creation evidence' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> OLD.lock_version + 1
           OR NEW.updated_at < OLD.updated_at
           OR NEW.updated_at > clock_timestamp() + interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 3 revision evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_reject_m3_evidence_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Module 3 evidence is append-only' USING ERRCODE = '55000';
END;
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'patient_profiles','patient_identifiers','patient_contacts','patient_addresses',
        'communication_preferences','caregiver_relationships','patient_authority_grants',
        'patient_portal_links','patient_consents','privacy_restrictions','patient_safety_flags',
        'patient_match_keys','patient_duplicate_candidates','patient_merge_requests',
        'patient_merge_decisions','patient_registration_runs'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id = nullif(current_setting(''app.current_organization_id'', true), '''')::uuid) WITH CHECK (organization_id = nullif(current_setting(''app.current_organization_id'', true), '''')::uuid)',
            table_name || '_tenant_policy', table_name);
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m3_tenant_write()',
            table_name || '_validate_write', table_name);
    END LOOP;
END;
$$;

CREATE TRIGGER patient_merge_decisions_append_only
    BEFORE UPDATE OR DELETE ON patient_merge_decisions
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m3_evidence_mutation();

REVOKE ALL ON
    patient_profiles, patient_identifiers, patient_contacts, patient_addresses,
    communication_preferences, caregiver_relationships, patient_authority_grants,
    patient_portal_links, patient_consents, privacy_restrictions, patient_safety_flags,
    patient_match_keys, patient_duplicate_candidates, patient_merge_requests,
    patient_merge_decisions, patient_registration_runs
FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON
    patient_profiles, patient_identifiers, patient_contacts, patient_addresses,
    communication_preferences, caregiver_relationships, patient_authority_grants,
    patient_portal_links, patient_consents, privacy_restrictions, patient_safety_flags,
    patient_match_keys, patient_duplicate_candidates, patient_merge_requests,
    patient_registration_runs
TO "${applicationRole}";

GRANT SELECT, INSERT ON patient_merge_decisions TO "${applicationRole}";

COMMENT ON TABLE patient_profiles IS
    'Organization-local canonical patient identity; a patient is not a portal account.';
COMMENT ON TABLE caregiver_relationships IS
    'Relationship fact only; authority is held separately in patient_authority_grants.';
COMMENT ON TABLE communication_preferences IS
    'Communication preference only; it does not establish consent or legal authority.';
COMMENT ON TABLE patient_duplicate_candidates IS
    'Explainable organization-local review candidates; rows never authorize automatic merge.';
