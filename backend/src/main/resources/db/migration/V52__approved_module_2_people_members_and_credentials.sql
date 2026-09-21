CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE person_profiles (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    legal_given_name varchar(100) NOT NULL,
    legal_family_name varchar(100) NOT NULL,
    middle_names varchar(150),
    chosen_name varchar(150),
    display_name varchar(150) NOT NULL,
    birth_date date,
    pronouns_key varchar(80),
    preferred_locale varchar(35),
    identity_revision bigint NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CHECK (legal_given_name = btrim(legal_given_name) AND char_length(legal_given_name) BETWEEN 1 AND 100),
    CHECK (legal_family_name = btrim(legal_family_name) AND char_length(legal_family_name) BETWEEN 1 AND 100),
    CHECK (middle_names IS NULL OR char_length(btrim(middle_names)) BETWEEN 1 AND 150),
    CHECK (chosen_name IS NULL OR char_length(btrim(chosen_name)) BETWEEN 1 AND 150),
    CHECK (display_name = btrim(display_name) AND char_length(display_name) BETWEEN 1 AND 150),
    CHECK (preferred_locale IS NULL OR preferred_locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01'),
    CHECK (status IN ('active','merged','deceased','restricted')),
    CHECK (identity_revision >= 0 AND lock_version >= 0),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at)
);

CREATE TABLE person_profile_aliases (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    person_id uuid NOT NULL REFERENCES person_profiles(id),
    alias_type varchar(32) NOT NULL,
    given_name varchar(100) NOT NULL,
    family_name varchar(100) NOT NULL,
    middle_names varchar(150),
    normalized_alias varchar(360) NOT NULL,
    source_code varchar(80) NOT NULL,
    verification_code varchar(80),
    effective_from date NOT NULL,
    effective_to date,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (person_id, normalized_alias, effective_from),
    CHECK (alias_type IN ('former_name','alternate_spelling','professional_name')),
    CHECK (char_length(btrim(given_name)) BETWEEN 1 AND 100),
    CHECK (char_length(btrim(family_name)) BETWEEN 1 AND 100),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded')),
    CHECK (lock_version >= 0)
);

CREATE TABLE person_contacts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    person_id uuid NOT NULL REFERENCES person_profiles(id),
    channel varchar(16) NOT NULL,
    contact_use varchar(24) NOT NULL,
    encrypted_value bytea NOT NULL,
    normalized_digest char(64) NOT NULL,
    masked_display varchar(160) NOT NULL,
    verification_state varchar(24) NOT NULL DEFAULT 'unverified',
    primary_contact boolean NOT NULL DEFAULT false,
    preferred_contact boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CHECK (channel IN ('email','phone')),
    CHECK (contact_use IN ('work','personal','emergency')),
    CHECK (normalized_digest ~ '^[0-9a-f]{64}$'),
    CHECK (char_length(masked_display) BETWEEN 3 AND 160),
    CHECK (verification_state IN ('unverified','pending','verified','failed')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded')),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX person_contacts_one_primary_uq
    ON person_contacts (person_id, channel, contact_use)
    WHERE primary_contact AND status = 'active' AND effective_to IS NULL;

CREATE TABLE person_addresses (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    person_id uuid NOT NULL REFERENCES person_profiles(id),
    address_use varchar(24) NOT NULL,
    line_1 varchar(160) NOT NULL,
    line_2 varchar(160),
    locality varchar(100) NOT NULL,
    region varchar(100),
    postal_code varchar(32),
    country_code char(2) NOT NULL,
    validation_state varchar(24) NOT NULL DEFAULT 'unvalidated',
    validation_source varchar(80),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CHECK (address_use IN ('home','correspondence')),
    CHECK (char_length(btrim(line_1)) BETWEEN 1 AND 160),
    CHECK (char_length(btrim(locality)) BETWEEN 1 AND 100),
    CHECK (country_code ~ '^[A-Z]{2}$'),
    CHECK (validation_state IN ('unvalidated','valid','invalid','manual_review')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','ended','superseded')),
    CHECK (lock_version >= 0)
);

CREATE TABLE organization_person_links (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    person_id uuid NOT NULL REFERENCES person_profiles(id),
    display_label varchar(150) NOT NULL,
    relationship_status varchar(24) NOT NULL DEFAULT 'candidate',
    source_workforce_member_id uuid,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'candidate',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    CHECK (char_length(btrim(display_label)) BETWEEN 1 AND 150),
    CHECK (relationship_status IN ('candidate','active','ended','merged')),
    CHECK (status = relationship_status),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX organization_person_links_one_live_person_uq
    ON organization_person_links (organization_id, person_id)
    WHERE relationship_status IN ('candidate','active') AND effective_to IS NULL;

CREATE TABLE person_match_keys (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    organization_person_link_id uuid NOT NULL,
    key_type varchar(40) NOT NULL,
    hmac_key_version varchar(80) NOT NULL,
    keyed_digest char(64) NOT NULL,
    blocking_bucket char(64),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, key_type, hmac_key_version, keyed_digest, organization_person_link_id),
    FOREIGN KEY (organization_id, organization_person_link_id)
        REFERENCES organization_person_links(organization_id, id),
    CHECK (key_type IN ('legal_name_birth_date','email','phone','regulated_identifier')),
    CHECK (keyed_digest ~ '^[0-9a-f]{64}$'),
    CHECK (blocking_bucket IS NULL OR blocking_bucket ~ '^[0-9a-f]{64}$'),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('active','rotated','revoked')),
    CHECK (lock_version >= 0)
);

CREATE TABLE person_merge_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    retained_link_id uuid NOT NULL,
    discarded_link_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    candidate_evidence_digest char(64) NOT NULL,
    impact_digest char(64) NOT NULL,
    decision_state varchar(24) NOT NULL DEFAULT 'draft',
    decision_by uuid,
    reason_code varchar(80),
    submitted_at timestamptz,
    decided_at timestamptz,
    executed_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, retained_link_id) REFERENCES organization_person_links(organization_id, id),
    FOREIGN KEY (organization_id, discarded_link_id) REFERENCES organization_person_links(organization_id, id),
    CHECK (retained_link_id <> discarded_link_id),
    CHECK (candidate_evidence_digest ~ '^[0-9a-f]{64}$' AND impact_digest ~ '^[0-9a-f]{64}$'),
    CHECK (decision_state IN ('draft','submitted','approved','rejected','executed','cancelled')),
    CHECK (status = decision_state),
    CHECK (decision_by IS NULL OR decision_by <> requested_by),
    CHECK (lock_version >= 0)
);

CREATE TABLE workforce_members (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    organization_person_link_id uuid NOT NULL,
    pathway varchar(24) NOT NULL,
    member_number varchar(48) NOT NULL,
    account_access_intent varchar(32) NOT NULL DEFAULT 'deferred',
    proposed_start_date date,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft',
    current_readiness_run_id uuid,
    activated_at timestamptz,
    offboarded_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, member_number),
    FOREIGN KEY (organization_id, organization_person_link_id)
        REFERENCES organization_person_links(organization_id, id),
    CHECK (pathway IN ('clinical','non_clinical')),
    CHECK (member_number ~ '^[A-Z0-9][A-Z0-9_-]{2,47}$'),
    CHECK (account_access_intent IN ('existing_user','invitation','none_required','deferred')),
    CHECK (lifecycle_state IN ('draft','submitted','active','suspended','offboarding','offboarded')),
    CHECK (status = lifecycle_state),
    CHECK ((lifecycle_state IN ('active','suspended','offboarding','offboarded')) = (activated_at IS NOT NULL)),
    CHECK ((lifecycle_state = 'offboarded') = (offboarded_at IS NOT NULL)),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX workforce_members_one_live_link_uq
    ON workforce_members (organization_id, organization_person_link_id)
    WHERE lifecycle_state <> 'offboarded';

ALTER TABLE organization_person_links
    ADD CONSTRAINT organization_person_links_source_member_fk
        FOREIGN KEY (organization_id, source_workforce_member_id)
        REFERENCES workforce_members(organization_id, id);

CREATE TABLE workforce_identifiers (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL,
    identifier_type_key varchar(80) NOT NULL,
    authority varchar(160) NOT NULL,
    normalized_value bytea NOT NULL,
    value_digest char(64) NOT NULL,
    masked_display varchar(80) NOT NULL,
    issued_on date,
    expires_on date,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    verification_state varchar(24) NOT NULL DEFAULT 'unverified',
    primary_identifier boolean NOT NULL DEFAULT false,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, workforce_member_id) REFERENCES workforce_members(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES workforce_identifiers(organization_id, id),
    CHECK (value_digest ~ '^[0-9a-f]{64}$'),
    CHECK (expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (verification_state IN ('unverified','pending','verified','rejected','revoked')),
    CHECK (status IN ('active','revoked','superseded')),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX workforce_identifiers_live_value_uq
    ON workforce_identifiers (organization_id, identifier_type_key, authority, value_digest)
    WHERE status <> 'revoked';

CREATE TABLE employment_engagements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL,
    engagement_type varchar(24) NOT NULL,
    employment_category_key varchar(80) NOT NULL,
    manager_membership_id uuid,
    work_email_contact_id uuid,
    work_phone_contact_id uuid,
    encrypted_external_reference bytea,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, workforce_member_id) REFERENCES workforce_members(organization_id, id),
    FOREIGN KEY (organization_id, manager_membership_id) REFERENCES organization_memberships(organization_id, id),
    FOREIGN KEY (work_email_contact_id) REFERENCES person_contacts(id),
    FOREIGN KEY (work_phone_contact_id) REFERENCES person_contacts(id),
    CHECK (engagement_type IN ('employee','contractor','volunteer','visiting','agency')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('draft','scheduled','active','suspended','ended','cancelled')),
    CHECK (lock_version >= 0)
);

CREATE INDEX employment_engagements_member_effective_idx
    ON employment_engagements (organization_id, workforce_member_id, effective_from DESC, id);

CREATE TABLE practitioner_profiles (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL,
    profession_entry_id uuid NOT NULL,
    profession_version_id uuid NOT NULL,
    regulated boolean NOT NULL,
    clinical_title varchar(160) NOT NULL,
    primary_registration_id uuid,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft',
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, workforce_member_id),
    FOREIGN KEY (organization_id, workforce_member_id) REFERENCES workforce_members(organization_id, id),
    CHECK (char_length(btrim(clinical_title)) BETWEEN 2 AND 160),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (lifecycle_state IN ('draft','active','suspended','ended')),
    CHECK (status = lifecycle_state),
    CHECK (lock_version >= 0)
);

CREATE TABLE professional_registrations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_profile_id uuid NOT NULL,
    regulator_entry_id uuid NOT NULL,
    regulator_version_id uuid NOT NULL,
    registration_type_entry_id uuid NOT NULL,
    registration_type_version_id uuid NOT NULL,
    encrypted_number bytea NOT NULL,
    number_digest char(64) NOT NULL,
    masked_display varchar(80) NOT NULL,
    jurisdiction_country char(2) NOT NULL,
    jurisdiction_region varchar(100),
    issued_on date,
    valid_from date NOT NULL,
    expires_on date,
    authority_status_code varchar(80),
    decision_reference_id uuid,
    supersedes_id uuid,
    status varchar(32) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, practitioner_profile_id) REFERENCES practitioner_profiles(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES professional_registrations(organization_id, id),
    CHECK (number_digest ~ '^[0-9a-f]{64}$'),
    CHECK (jurisdiction_country ~ '^[A-Z]{2}$'),
    CHECK (issued_on IS NULL OR issued_on <= valid_from),
    CHECK (expires_on IS NULL OR expires_on >= valid_from),
    CHECK (status IN ('draft','evidence_pending','submitted','verified','suspended','revoked','expired','superseded')),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (lock_version >= 0)
);

CREATE UNIQUE INDEX professional_registrations_live_number_uq
    ON professional_registrations (organization_id, regulator_entry_id, registration_type_entry_id, number_digest)
    WHERE status NOT IN ('revoked','expired','superseded');

ALTER TABLE practitioner_profiles
    ADD CONSTRAINT practitioner_profiles_primary_registration_fk
        FOREIGN KEY (organization_id, primary_registration_id)
        REFERENCES professional_registrations(organization_id, id);

CREATE TABLE qualifications (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL,
    practitioner_profile_id uuid,
    qualification_entry_id uuid NOT NULL,
    qualification_version_id uuid NOT NULL,
    awarding_body varchar(200) NOT NULL,
    country_code char(2) NOT NULL,
    awarded_on date NOT NULL,
    expires_on date,
    result_classification varchar(160),
    verification_reference_id uuid,
    supersedes_id uuid,
    status varchar(32) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, workforce_member_id) REFERENCES workforce_members(organization_id, id),
    FOREIGN KEY (organization_id, practitioner_profile_id) REFERENCES practitioner_profiles(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES qualifications(organization_id, id),
    CHECK (char_length(btrim(awarding_body)) BETWEEN 2 AND 200),
    CHECK (country_code ~ '^[A-Z]{2}$'),
    CHECK (expires_on IS NULL OR expires_on >= awarded_on),
    CHECK (status IN ('draft','submitted','verified','rejected','returned_for_correction','superseded')),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (lock_version >= 0)
);

CREATE TABLE practitioner_specialties (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_profile_id uuid NOT NULL,
    specialty_entry_id uuid NOT NULL,
    specialty_version_id uuid NOT NULL,
    designation varchar(16) NOT NULL,
    evidence_reference_id uuid,
    decision_reference_id uuid,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'scheduled',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, practitioner_profile_id) REFERENCES practitioner_profiles(organization_id, id),
    CHECK (designation IN ('primary','secondary')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (status IN ('scheduled','active','ended','superseded')),
    CHECK (lock_version >= 0)
);

ALTER TABLE practitioner_specialties
    ADD CONSTRAINT practitioner_specialties_one_primary_excl
    EXCLUDE USING gist (
        organization_id WITH =,
        practitioner_profile_id WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&
    ) WHERE (designation = 'primary' AND status IN ('scheduled','active'));

CREATE TABLE practitioner_credentials (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_profile_id uuid,
    workforce_member_id uuid NOT NULL,
    credential_type_entry_id uuid NOT NULL,
    credential_type_version_id uuid NOT NULL,
    issuer varchar(200) NOT NULL,
    issued_on date,
    expires_on date,
    risk_tier varchar(24) NOT NULL,
    content_digest char(64) NOT NULL,
    submitted_by uuid,
    submitted_at timestamptz,
    review_started_at timestamptz,
    current_verification_id uuid,
    supersedes_id uuid,
    status varchar(40) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, practitioner_profile_id) REFERENCES practitioner_profiles(organization_id, id),
    FOREIGN KEY (organization_id, workforce_member_id) REFERENCES workforce_members(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id) REFERENCES practitioner_credentials(organization_id, id),
    CHECK (char_length(btrim(issuer)) BETWEEN 2 AND 200),
    CHECK (expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on),
    CHECK (risk_tier IN ('low','moderate','high','critical')),
    CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('draft','evidence_pending','scanning','submitted','in_review','verified','rejected','more_information_required','returned_for_correction','suspended','revoked','expired','superseded')),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (lock_version >= 0)
);

CREATE TABLE credential_documents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_credential_id uuid NOT NULL,
    platform_document_id uuid NOT NULL,
    platform_object_version_id uuid NOT NULL,
    purpose varchar(80) NOT NULL DEFAULT 'credential_verification',
    declared_media_type varchar(120) NOT NULL,
    declared_file_name varchar(255) NOT NULL,
    declared_size bigint NOT NULL,
    declared_sha256 char(64) NOT NULL,
    retention_class varchar(80) NOT NULL,
    promoted_evidence_digest char(64),
    uploaded_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'intent_created',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, platform_document_id, platform_object_version_id),
    FOREIGN KEY (organization_id, practitioner_credential_id) REFERENCES practitioner_credentials(organization_id, id),
    FOREIGN KEY (organization_id, platform_document_id, platform_object_version_id)
        REFERENCES document_quarantine_evidence(organization_id, document_id, object_version_id),
    CHECK (purpose = 'credential_verification'),
    CHECK (declared_media_type ~ '^[a-z0-9.+-]+/[a-z0-9.+-]+$'),
    CHECK (char_length(declared_file_name) BETWEEN 1 AND 255 AND declared_file_name !~ '[\\/[:cntrl:]]'),
    CHECK (declared_size BETWEEN 1 AND 26214400),
    CHECK (declared_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (promoted_evidence_digest IS NULL OR promoted_evidence_digest ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('intent_created','uploading','quarantined','scanning','clean','infected','invalid','failed','disposed')),
    CHECK (lock_version >= 0)
);

CREATE TABLE credential_scan_attempts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    credential_document_id uuid NOT NULL,
    platform_scan_attestation_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    scanner_identity varchar(120) NOT NULL,
    scanner_version varchar(120) NOT NULL,
    signature_version varchar(120) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    outcome varchar(24) NOT NULL,
    failure_code varchar(120),
    status varchar(24) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, credential_document_id, attempt_number),
    FOREIGN KEY (organization_id, credential_document_id) REFERENCES credential_documents(organization_id, id),
    CHECK (attempt_number > 0),
    CHECK (outcome IN ('clean','infected','invalid','unavailable','failed')),
    CHECK (status = outcome),
    CHECK (completed_at IS NULL OR completed_at >= started_at),
    CHECK ((outcome IN ('unavailable','failed')) = (failure_code IS NOT NULL)),
    CHECK (lock_version >= 0)
);

CREATE TABLE credential_verifications (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_credential_id uuid NOT NULL,
    credential_revision bigint NOT NULL,
    credential_digest char(64) NOT NULL,
    reviewer_id uuid NOT NULL,
    decision varchar(40) NOT NULL,
    decision_reason_code varchar(80) NOT NULL,
    protected_note_reference uuid,
    evidence_ids uuid[] NOT NULL,
    evidence_digest char(64) NOT NULL,
    policy_version varchar(80) NOT NULL,
    registry_version varchar(80) NOT NULL,
    decided_at timestamptz NOT NULL,
    status varchar(40) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, practitioner_credential_id, credential_revision, credential_digest),
    FOREIGN KEY (organization_id, practitioner_credential_id) REFERENCES practitioner_credentials(organization_id, id),
    CHECK (credential_revision >= 0),
    CHECK (credential_digest ~ '^[0-9a-f]{64}$' AND evidence_digest ~ '^[0-9a-f]{64}$'),
    CHECK (cardinality(evidence_ids) BETWEEN 1 AND 32 AND array_position(evidence_ids, NULL) IS NULL),
    CHECK (decision IN ('verified','rejected','more_information_required','returned_for_correction')),
    CHECK (status = decision),
    CHECK (lock_version = 0),
    CHECK (isfinite(decided_at))
);

ALTER TABLE practitioner_credentials
    ADD CONSTRAINT practitioner_credentials_current_verification_fk
        FOREIGN KEY (organization_id, current_verification_id)
        REFERENCES credential_verifications(organization_id, id);

CREATE FUNCTION careos_validate_m2_tenant_write()
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
             AND operation.registry_version = 'm2-candidate-1'
             AND operation.status = 'active'
       ) THEN
        RAISE EXCEPTION 'invalid Module 2 tenant write context' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> 0 THEN
            RAISE EXCEPTION 'invalid Module 2 creation evidence' USING ERRCODE = '23514';
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
            RAISE EXCEPTION 'invalid Module 2 revision evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_m2_person_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_actor uuid := nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text := nullif(current_setting('app.current_operation_key', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN RETURN NEW; END IF;
    IF configured_actor IS NULL OR configured_operation NOT IN
        ('workforce.member.create','workforce.person.match','workforce.person.correct','workforce.member.manage') THEN
        RAISE EXCEPTION 'invalid Module 2 person write context' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> 0 THEN
            RAISE EXCEPTION 'invalid person creation evidence' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> OLD.lock_version + 1 THEN
            RAISE EXCEPTION 'invalid person revision evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_reject_m2_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 2 evidence is append-only' USING ERRCODE = '55000';
END;
$$;

ALTER TABLE person_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE person_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY person_profiles_organization_link_policy ON person_profiles
    USING (EXISTS (
        SELECT 1 FROM organization_person_links links
        WHERE links.person_id = person_profiles.id
          AND links.organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
          AND links.relationship_status <> 'merged'
    ))
    WITH CHECK (
        nullif(current_setting('app.current_operation_key', true), '') IN
            ('workforce.member.create','workforce.person.match')
        OR EXISTS (
            SELECT 1 FROM organization_person_links links
            WHERE links.person_id = person_profiles.id
              AND links.organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
              AND links.relationship_status <> 'merged'
        )
    );

CREATE TRIGGER person_profiles_validate_write
    BEFORE INSERT OR UPDATE ON person_profiles
    FOR EACH ROW EXECUTE FUNCTION careos_validate_m2_person_write();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['person_profile_aliases','person_contacts','person_addresses'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (EXISTS (SELECT 1 FROM organization_person_links links WHERE links.person_id = %I.person_id AND links.organization_id = nullif(current_setting(''app.current_organization_id'', true), '''')::uuid AND links.relationship_status <> ''merged'')) WITH CHECK (EXISTS (SELECT 1 FROM organization_person_links links WHERE links.person_id = %I.person_id AND links.organization_id = nullif(current_setting(''app.current_organization_id'', true), '''')::uuid AND links.relationship_status <> ''merged''))',
            table_name || '_organization_link_policy', table_name, table_name, table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m2_person_write()', table_name || '_validate_write', table_name);
    END LOOP;
END;
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'organization_person_links','person_match_keys','person_merge_requests','workforce_members',
        'workforce_identifiers','employment_engagements','practitioner_profiles',
        'professional_registrations','qualifications','practitioner_specialties',
        'practitioner_credentials','credential_documents','credential_scan_attempts',
        'credential_verifications'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id = nullif(current_setting(''app.current_organization_id'', true), '''')::uuid) WITH CHECK (organization_id = nullif(current_setting(''app.current_organization_id'', true), '''')::uuid)',
            table_name || '_tenant_policy', table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m2_tenant_write()', table_name || '_validate_write', table_name);
    END LOOP;
END;
$$;

CREATE TRIGGER credential_scan_attempts_append_only
    BEFORE UPDATE OR DELETE ON credential_scan_attempts
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m2_evidence_mutation();
CREATE TRIGGER credential_verifications_append_only
    BEFORE UPDATE OR DELETE ON credential_verifications
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m2_evidence_mutation();

REVOKE ALL ON person_profiles, person_profile_aliases, person_contacts, person_addresses,
    organization_person_links, person_match_keys, person_merge_requests, workforce_members,
    workforce_identifiers, employment_engagements, practitioner_profiles,
    professional_registrations, qualifications, practitioner_specialties,
    practitioner_credentials, credential_documents, credential_scan_attempts,
    credential_verifications FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON person_profiles, person_profile_aliases, person_contacts,
    person_addresses, organization_person_links, person_match_keys, person_merge_requests,
    workforce_members, workforce_identifiers, employment_engagements, practitioner_profiles,
    professional_registrations, qualifications, practitioner_specialties,
    practitioner_credentials, credential_documents TO "${applicationRole}";
GRANT SELECT, INSERT ON credential_scan_attempts, credential_verifications TO "${applicationRole}";

CREATE INDEX workforce_members_directory_idx
    ON workforce_members (organization_id, lifecycle_state, member_number, id);
CREATE INDEX credential_review_queue_idx
    ON practitioner_credentials (organization_id, status, risk_tier DESC, submitted_at, id)
    WHERE status IN ('submitted','in_review');
CREATE INDEX credential_expiry_idx
    ON practitioner_credentials (organization_id, expires_on, id)
    WHERE status IN ('verified','suspended');
CREATE INDEX registration_expiry_idx
    ON professional_registrations (organization_id, expires_on, id)
    WHERE status IN ('verified','suspended');
