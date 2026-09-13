CREATE TABLE authorization_permissions (
    permission_key varchar(120) PRIMARY KEY,
    display_name varchar(160) NOT NULL,
    description text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (permission_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (status IN ('active', 'retired'))
);

CREATE TABLE authorization_roles (
    role_key varchar(100) PRIMARY KEY,
    display_name varchar(160) NOT NULL,
    description text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (role_key ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (status IN ('active', 'retired'))
);

CREATE TABLE authorization_role_permissions (
    role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    permission_key varchar(120) NOT NULL REFERENCES authorization_permissions(permission_key),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (role_key, permission_key)
);

REVOKE ALL ON authorization_permissions FROM PUBLIC;
REVOKE ALL ON authorization_roles FROM PUBLIC;
REVOKE ALL ON authorization_role_permissions FROM PUBLIC;
GRANT SELECT ON authorization_permissions TO "${applicationRole}";
GRANT SELECT ON authorization_roles TO "${applicationRole}";
GRANT SELECT ON authorization_role_permissions TO "${applicationRole}";

COMMENT ON TABLE authorization_permissions IS
    'Migration-owned, owner-approved permission registry. Empty is intentional and fails closed.';
COMMENT ON TABLE authorization_roles IS
    'Migration-owned, owner-approved role registry. Unknown membership role keys grant no permissions.';
COMMENT ON TABLE authorization_role_permissions IS
    'Migration-owned role-to-permission grants. Runtime application role has read-only access.';
