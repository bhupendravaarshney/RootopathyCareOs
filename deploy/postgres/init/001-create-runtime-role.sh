#!/bin/sh
set -eu

: "${CAREOS_DB_APP_USER:?CAREOS_DB_APP_USER is required}"
: "${CAREOS_DB_APP_PASSWORD:?CAREOS_DB_APP_PASSWORD is required}"

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
