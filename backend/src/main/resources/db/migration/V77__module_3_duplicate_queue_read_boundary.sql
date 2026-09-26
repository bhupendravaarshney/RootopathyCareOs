-- Some approved permissions protect both a minimum-necessary screen read and
-- its governed mutations. Reads carry no mutation reason, while each action
-- still requires one at the application contract. Keep these shared
-- authorization operations usable for reads without weakening action checks.
UPDATE authorization_operations
SET reason_required=false
WHERE operation_key IN (
      'patient.registration.start',
      'patient.registration.manage',
      'patient.duplicate.review')
  AND registry_version='m3-candidate-1';
