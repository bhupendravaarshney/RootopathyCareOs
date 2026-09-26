-- Keep idempotency operation identifiers aligned with the authorization
-- registry's approved lower-case key grammar, including underscore-bearing
-- segments such as appointment.no_show.
ALTER TABLE idempotency_records
    DROP CONSTRAINT idempotency_records_operation_check,
    ADD CONSTRAINT idempotency_records_operation_check
        CHECK (operation_key ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$');

