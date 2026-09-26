-- Extend the approved safe event vocabulary with opaque lineage references.
-- These identifiers make primary/preferred designation and preference history
-- reconstructable without placing contact/address values into evidence.
UPDATE audit_event_definitions
SET allowed_payload_keys = allowed_payload_keys || ARRAY['priorPrimaryReferences']::text[]
WHERE event_name IN ('patient.contact.changed','patient.address.changed')
  AND schema_version=1
  AND registry_version='m3-candidate-1'
  AND NOT allowed_payload_keys @> ARRAY['priorPrimaryReferences']::text[];

UPDATE audit_event_definitions
SET allowed_payload_keys = allowed_payload_keys || ARRAY['predecessorReference']::text[]
WHERE event_name='patient.preference.changed'
  AND schema_version=1
  AND registry_version='m3-candidate-1'
  AND NOT allowed_payload_keys @> ARRAY['predecessorReference']::text[];
