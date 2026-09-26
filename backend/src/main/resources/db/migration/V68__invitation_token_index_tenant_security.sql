-- The invitation token index is a restricted, cross-tenant lookup used before an
-- invitation recipient has an authenticated tenant context. Keep direct access
-- tenant-isolated while allowing the SECURITY DEFINER lookup to expose only the
-- row addressed by the caller's validated HMAC token hash.

ALTER TABLE invitation_token_index ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation_token_index FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS invitation_token_index_tenant_policy ON invitation_token_index;
CREATE POLICY invitation_token_index_tenant_policy ON invitation_token_index
    USING (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR token_hash::text =
            nullif(current_setting('app.current_invitation_token_hash', true), '')
    )
    WITH CHECK (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
    );

CREATE OR REPLACE FUNCTION careos_lookup_invitation_token(candidate_hash text)
RETURNS TABLE (
    invitation_id uuid,
    organization_id uuid,
    target_user_id uuid,
    expires_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public
AS $$
DECLARE
    previous_candidate_hash text :=
        current_setting('app.current_invitation_token_hash', true);
BEGIN
    IF candidate_hash IS NULL OR candidate_hash !~ '^[0-9a-f]{64}$' THEN
        RETURN;
    END IF;

    PERFORM set_config('app.current_invitation_token_hash', candidate_hash, true);

    RETURN QUERY
    SELECT token_index.invitation_id,
           token_index.organization_id,
           token_index.target_user_id,
           token_index.expires_at
    FROM public.invitation_token_index token_index
    WHERE token_index.token_hash = candidate_hash
      AND token_index.expires_at > clock_timestamp();

    PERFORM set_config(
        'app.current_invitation_token_hash',
        coalesce(previous_candidate_hash, ''),
        true);
EXCEPTION
    WHEN OTHERS THEN
        PERFORM set_config(
            'app.current_invitation_token_hash',
            coalesce(previous_candidate_hash, ''),
            true);
        RAISE;
END;
$$;

REVOKE ALL ON FUNCTION careos_lookup_invitation_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_lookup_invitation_token(text) TO "${applicationRole}";

COMMENT ON POLICY invitation_token_index_tenant_policy ON invitation_token_index IS
    'Restricts direct access to the active tenant and pre-authentication lookup to one validated HMAC token hash.';
COMMENT ON FUNCTION careos_lookup_invitation_token(text) IS
    'Returns only the unexpired invitation addressed by one validated HMAC token hash without granting direct table access.';
