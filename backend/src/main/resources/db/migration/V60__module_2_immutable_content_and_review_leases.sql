-- Enforce immutable submitted/approved content and durable credential-review ownership.

ALTER TABLE practitioner_credentials
    ADD COLUMN reviewer_id uuid REFERENCES users(id),
    ADD COLUMN review_lease_expires_at timestamptz,
    ADD CONSTRAINT practitioner_credentials_review_lease_shape_check
        CHECK ((reviewer_id IS NULL)=(review_lease_expires_at IS NULL));

CREATE INDEX practitioner_credentials_review_lease_idx
    ON practitioner_credentials(organization_id,review_lease_expires_at,id)
    WHERE status='in_review';

CREATE FUNCTION careos_guard_qualification_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status<>'draft'
       AND ROW(NEW.workforce_member_id,NEW.practitioner_profile_id,
               NEW.qualification_entry_id,NEW.qualification_version_id,
               NEW.awarding_body,NEW.country_code,NEW.awarded_on,NEW.expires_on,
               NEW.result_classification,NEW.supersedes_id)
           IS DISTINCT FROM
           ROW(OLD.workforce_member_id,OLD.practitioner_profile_id,
               OLD.qualification_entry_id,OLD.qualification_version_id,
               OLD.awarding_body,OLD.country_code,OLD.awarded_on,OLD.expires_on,
               OLD.result_classification,OLD.supersedes_id) THEN
        RAISE EXCEPTION 'submitted qualification content is immutable'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qualifications_guard_content
    BEFORE UPDATE ON qualifications
    FOR EACH ROW EXECUTE FUNCTION careos_guard_qualification_content();

CREATE FUNCTION careos_guard_registration_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status<>'draft'
       AND ROW(NEW.practitioner_profile_id,NEW.regulator_entry_id,NEW.regulator_version_id,
               NEW.registration_type_entry_id,NEW.registration_type_version_id,
               NEW.encrypted_number,NEW.number_digest,NEW.masked_display,
               NEW.jurisdiction_country,NEW.jurisdiction_region,NEW.issued_on,
               NEW.valid_from,NEW.expires_on,NEW.authority_status_code,NEW.supersedes_id)
           IS DISTINCT FROM
           ROW(OLD.practitioner_profile_id,OLD.regulator_entry_id,OLD.regulator_version_id,
               OLD.registration_type_entry_id,OLD.registration_type_version_id,
               OLD.encrypted_number,OLD.number_digest,OLD.masked_display,
               OLD.jurisdiction_country,OLD.jurisdiction_region,OLD.issued_on,
               OLD.valid_from,OLD.expires_on,OLD.authority_status_code,OLD.supersedes_id) THEN
        RAISE EXCEPTION 'submitted registration content is immutable'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_registrations_guard_content
    BEFORE UPDATE ON professional_registrations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_registration_content();

CREATE FUNCTION careos_guard_credential_content_and_review()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status NOT IN ('draft','evidence_pending')
       AND ROW(NEW.practitioner_profile_id,NEW.workforce_member_id,
               NEW.credential_type_entry_id,NEW.credential_type_version_id,
               NEW.issuer,NEW.issued_on,NEW.expires_on,NEW.risk_tier,
               NEW.supersedes_id,NEW.content_digest)
           IS DISTINCT FROM
           ROW(OLD.practitioner_profile_id,OLD.workforce_member_id,
               OLD.credential_type_entry_id,OLD.credential_type_version_id,
               OLD.issuer,OLD.issued_on,OLD.expires_on,OLD.risk_tier,
               OLD.supersedes_id,OLD.content_digest) THEN
        RAISE EXCEPTION 'submitted credential content is immutable'
            USING ERRCODE='55000';
    END IF;
    IF current_user='${applicationRole}' AND OLD.status='submitted' AND NEW.status='in_review'
       AND (configured_operation<>'credential.review.claim'
         OR NEW.reviewer_id IS DISTINCT FROM configured_actor
         OR NEW.review_lease_expires_at<=clock_timestamp()
         OR NEW.review_lease_expires_at>clock_timestamp()+interval '15 minutes') THEN
        RAISE EXCEPTION 'invalid credential review claim' USING ERRCODE='42501';
    END IF;
    IF current_user='${applicationRole}' AND OLD.status='in_review' AND NEW.status='in_review'
       AND (NEW.reviewer_id IS DISTINCT FROM OLD.reviewer_id
         OR NEW.review_lease_expires_at IS DISTINCT FROM OLD.review_lease_expires_at)
       AND (configured_operation<>'credential.review.claim'
         OR OLD.review_lease_expires_at>clock_timestamp()
         OR NEW.reviewer_id IS DISTINCT FROM configured_actor
         OR NEW.review_lease_expires_at<=clock_timestamp()
         OR NEW.review_lease_expires_at>clock_timestamp()+interval '15 minutes') THEN
        RAISE EXCEPTION 'invalid expired credential review reclaim' USING ERRCODE='42501';
    END IF;
    IF current_user='${applicationRole}' AND OLD.status='in_review'
       AND NEW.status IN ('verified','rejected','more_information_required','returned_for_correction')
       AND (configured_operation<>'credential.review.decide'
         OR OLD.reviewer_id IS DISTINCT FROM configured_actor
         OR OLD.review_lease_expires_at<=clock_timestamp()
         OR NEW.current_verification_id IS NULL) THEN
        RAISE EXCEPTION 'credential review lease is not current' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER practitioner_credentials_guard_content_and_review
    BEFORE UPDATE ON practitioner_credentials
    FOR EACH ROW EXECUTE FUNCTION careos_guard_credential_content_and_review();

CREATE FUNCTION careos_guard_registry_definition_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'registry definitions are retained as historical evidence' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state='active'
       AND ROW(NEW.registry_key,NEW.category,NEW.display_name,NEW.owner_membership_id,
               NEW.value_schema,NEW.review_cadence_days)
           IS DISTINCT FROM
           ROW(OLD.registry_key,OLD.category,OLD.display_name,OLD.owner_membership_id,
               OLD.value_schema,OLD.review_cadence_days) THEN
        RAISE EXCEPTION 'active registry definition content is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workforce_registry_definitions_guard_content
    BEFORE UPDATE OR DELETE ON workforce_registry_definitions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_registry_definition_content();

CREATE FUNCTION careos_guard_registry_entry_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'registry entries are retained as historical evidence' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state='active'
       AND ROW(NEW.registry_definition_id,NEW.entry_key,NEW.code,NEW.display_label,
               NEW.jurisdiction_country,NEW.context_key)
           IS DISTINCT FROM
           ROW(OLD.registry_definition_id,OLD.entry_key,OLD.code,OLD.display_label,
               OLD.jurisdiction_country,OLD.context_key) THEN
        RAISE EXCEPTION 'active registry entry content is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workforce_registry_entries_guard_content
    BEFORE UPDATE OR DELETE ON workforce_registry_entries
    FOR EACH ROW EXECUTE FUNCTION careos_guard_registry_entry_content();

CREATE FUNCTION careos_require_credential_review_lease()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM practitioner_credentials credential
        WHERE credential.organization_id=NEW.organization_id
          AND credential.id=NEW.practitioner_credential_id
          AND credential.status='in_review'
          AND credential.reviewer_id=NEW.reviewer_id
          AND credential.review_lease_expires_at>clock_timestamp()
    ) THEN
        RAISE EXCEPTION 'credential review lease is not current' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER credential_verifications_require_review_lease
    BEFORE INSERT ON credential_verifications
    FOR EACH ROW EXECUTE FUNCTION careos_require_credential_review_lease();

CREATE FUNCTION careos_guard_scope_definition_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'scope definitions are retained as historical evidence' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state='active'
       AND ROW(NEW.definition_code,NEW.name,NEW.profession_entry_id,NEW.profession_version_id,
               NEW.specialty_entry_id,NEW.specialty_version_id,NEW.service_id,
               NEW.jurisdiction_country,NEW.jurisdiction_region,NEW.owner_membership_id,
               NEW.effective_from)
           IS DISTINCT FROM
           ROW(OLD.definition_code,OLD.name,OLD.profession_entry_id,OLD.profession_version_id,
               OLD.specialty_entry_id,OLD.specialty_version_id,OLD.service_id,
               OLD.jurisdiction_country,OLD.jurisdiction_region,OLD.owner_membership_id,
               OLD.effective_from) THEN
        RAISE EXCEPTION 'active scope definition content is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scope_definitions_guard_content
    BEFORE UPDATE OR DELETE ON scope_definitions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_definition_content();

CREATE FUNCTION careos_guard_scope_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'scope records are retained as historical evidence' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state NOT IN ('draft','changes_requested')
       AND ROW(NEW.practitioner_profile_id,NEW.scope_definition_id,
               NEW.definition_version_digest,NEW.effective_from,
               NEW.supersedes_id)
           IS DISTINCT FROM
           ROW(OLD.practitioner_profile_id,OLD.scope_definition_id,
               OLD.definition_version_digest,OLD.effective_from,
               OLD.supersedes_id) THEN
        RAISE EXCEPTION 'submitted scope content is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state NOT IN ('draft','changes_requested')
       AND NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (
           NEW.lifecycle_state IN ('ended','superseded')
           AND nullif(current_setting('app.current_operation_key',true),'')
               IN ('practitioner.scope.lifecycle','practitioner.scope.approve')
           AND NEW.effective_to>OLD.effective_from
           AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)
       ) THEN
        RAISE EXCEPTION 'submitted scope range is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scopes_of_practice_guard_content
    BEFORE UPDATE OR DELETE ON scopes_of_practice
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_content();

CREATE FUNCTION careos_guard_scope_requirement_content()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE definition_created_at timestamptz;
BEGIN
    IF TG_OP='INSERT' THEN
        SELECT created_at INTO definition_created_at
          FROM scope_definitions
         WHERE organization_id=NEW.organization_id AND id=NEW.scope_definition_id;
        IF definition_created_at<transaction_timestamp() THEN
            RAISE EXCEPTION 'active scope requirements are immutable' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='active' THEN
        RAISE EXCEPTION 'active scope requirements are immutable' USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scope_requirements_guard_content
    BEFORE INSERT OR UPDATE OR DELETE ON scope_requirements
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_requirement_content();

CREATE FUNCTION careos_guard_scope_child_content()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_scope_id uuid;
DECLARE parent_state text;
BEGIN
    parent_scope_id:=CASE WHEN TG_OP='DELETE' THEN OLD.scope_of_practice_id ELSE NEW.scope_of_practice_id END;
    SELECT lifecycle_state INTO parent_state FROM scopes_of_practice
     WHERE organization_id=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END
       AND id=parent_scope_id;
    IF parent_state IS NULL THEN
        RAISE EXCEPTION 'scope parent is unavailable' USING ERRCODE='23503';
    END IF;
    IF parent_state NOT IN ('draft','changes_requested') THEN
        RAISE EXCEPTION 'submitted scope content is immutable' USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scope_activities_guard_content
    BEFORE INSERT OR UPDATE OR DELETE ON scope_activities
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_child_content();
CREATE TRIGGER scope_restrictions_guard_content
    BEFORE INSERT OR UPDATE OR DELETE ON scope_restrictions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_child_content();

CREATE FUNCTION careos_guard_availability_profile_content()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'availability profiles are retained as historical evidence' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state IN ('scheduled','active','superseded')
       AND ROW(NEW.workforce_member_id,NEW.facility_id,NEW.timezone,NEW.profile_version,
               NEW.batch_revision,NEW.not_required,NEW.effective_from)
           IS DISTINCT FROM
           ROW(OLD.workforce_member_id,OLD.facility_id,OLD.timezone,OLD.profile_version,
               OLD.batch_revision,OLD.not_required,OLD.effective_from) THEN
        RAISE EXCEPTION 'scheduled availability content is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER availability_profiles_guard_content
    BEFORE UPDATE OR DELETE ON availability_profiles
    FOR EACH ROW EXECUTE FUNCTION careos_guard_availability_profile_content();

CREATE FUNCTION careos_guard_availability_child_content()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_created_at timestamptz;
BEGIN
    IF TG_OP='INSERT' THEN
        SELECT created_at INTO parent_created_at FROM availability_profiles
         WHERE organization_id=NEW.organization_id AND id=NEW.availability_profile_id;
        IF parent_created_at<transaction_timestamp() THEN
            RAISE EXCEPTION 'availability batch children are immutable' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'availability batch children are immutable' USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER availability_periods_guard_content
    BEFORE INSERT OR UPDATE OR DELETE ON availability_periods
    FOR EACH ROW EXECUTE FUNCTION careos_guard_availability_child_content();
CREATE TRIGGER availability_exceptions_guard_content
    BEFORE INSERT OR UPDATE OR DELETE ON availability_exceptions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_availability_child_content();

-- A renewal may temporarily coexist with its exact predecessor, but two unrelated
-- live registrations (or sibling successors) may never claim the same authority key.
DROP INDEX professional_registrations_live_number_uq;

CREATE INDEX professional_registrations_number_lookup_idx
    ON professional_registrations (
        organization_id,regulator_entry_id,registration_type_entry_id,number_digest);

CREATE FUNCTION careos_validate_registration_lineage_uniqueness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IN ('revoked','expired','superseded') THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.organization_id::text || ':' || NEW.regulator_entry_id::text || ':' ||
        NEW.registration_type_entry_id::text || ':' || NEW.number_digest,0));

    IF EXISTS (
        SELECT 1
        FROM professional_registrations other
        WHERE other.organization_id=NEW.organization_id
          AND other.regulator_entry_id=NEW.regulator_entry_id
          AND other.registration_type_entry_id=NEW.registration_type_entry_id
          AND other.number_digest=NEW.number_digest
          AND other.id<>NEW.id
          AND other.status NOT IN ('revoked','expired','superseded')
          AND (NEW.supersedes_id IS NULL OR other.id<>NEW.supersedes_id)
    ) THEN
        RAISE EXCEPTION 'a current registration already uses this authority key'
            USING ERRCODE='23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER professional_registrations_lineage_unique
BEFORE INSERT OR UPDATE OF organization_id,regulator_entry_id,
    registration_type_entry_id,number_digest,status,supersedes_id
ON professional_registrations
FOR EACH ROW EXECUTE FUNCTION careos_validate_registration_lineage_uniqueness();
