CREATE ROLE careos_app
    LOGIN
    PASSWORD 'careos-app-test-only'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOBYPASSRLS;

GRANT CONNECT ON DATABASE careos_test TO careos_app;
