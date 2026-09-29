#!/bin/sh
set -eu

: "${CAREOS_DB_APP_USER:?CAREOS_DB_APP_USER is required}"
: "${CAREOS_DB_APP_PASSWORD:?CAREOS_DB_APP_PASSWORD is required}"

local_reference_authority="${CAREOS_DB_LOCAL_REFERENCE_AUTHORITY:-false}"
case "$local_reference_authority" in
    true|false) ;;
    *)
        echo "CAREOS_DB_LOCAL_REFERENCE_AUTHORITY must be exactly true or false" >&2
        exit 1
        ;;
esac

psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=app_user="$CAREOS_DB_APP_USER" \
    --set=app_password="$CAREOS_DB_APP_PASSWORD" <<-'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS',
    :'app_user',
    :'app_password'
) \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SQL

if [ "$local_reference_authority" = "true" ]; then
    psql --set=ON_ERROR_STOP=1 \
        --username "$POSTGRES_USER" \
        --dbname "$POSTGRES_DB" \
        --set=app_user="$CAREOS_DB_APP_USER" <<-'SQL'
SELECT 'CREATE ROLE careos_local_reference_authority NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS'
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'careos_local_reference_authority'
) \gexec

ALTER ROLE careos_local_reference_authority
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

SELECT format(
    'GRANT careos_local_reference_authority TO %I WITH ADMIN FALSE, INHERIT FALSE, SET FALSE',
    :'app_user'
) \gexec
SQL
fi
