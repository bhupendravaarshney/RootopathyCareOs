CREATE ROLE careos_app
    LOGIN
    PASSWORD 'careos-app-test-only'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOBYPASSRLS;

CREATE ROLE careos_local_reference_authority
    NOLOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOBYPASSRLS;

GRANT careos_local_reference_authority TO careos_app
    WITH ADMIN FALSE, INHERIT FALSE, SET FALSE;

GRANT CONNECT ON DATABASE careos_test TO careos_app;
