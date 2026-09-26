export type ModuleKey = 'M1' | 'M2' | 'M3' | 'M4' | 'M5' | 'COS';

export type Screen = {
  id: string;
  module: ModuleKey;
  title: string;
  purpose: string;
  group: string;
};

const m1: Array<[string, string, string]> = [
  [
    'Login',
    'Authenticate securely and continue to the requested authorized workspace.',
    'Identity',
  ],
  [
    'Invitations',
    'Issue, inspect, revoke, and accept one-use administrator invitations.',
    'Identity',
  ],
  [
    'Multi-factor authentication',
    'Enroll, challenge, recover, replace factors, and govern administrative reset.',
    'Identity',
  ],
  ['Organization selector', 'Choose one currently authorized organization workspace.', 'Identity'],
  ['Administration dashboard', 'Review readiness, counts and exceptions.', 'Overview'],
  ['Setup checklist', 'Complete server-calculated organization setup gates.', 'Overview'],
  ['Organization profile', 'Manage legal and display identity.', 'Organization'],
  ['Registration and identifiers', 'Manage governed organization identifiers.', 'Organization'],
  ['Addresses and contacts', 'Maintain effective addresses and contact records.', 'Organization'],
  ['International settings', 'Configure locale, timezone and regional formats.', 'Organization'],
  [
    'Governance contacts',
    'Assign clinical, privacy, security and billing contacts.',
    'Organization',
  ],
  ['Facilities', 'Manage the organization care network.', 'Care network'],
  ['Facility wizard', 'Create a facility safely in draft state.', 'Care network'],
  ['Departments and units', 'Maintain the organizational hierarchy.', 'Care network'],
  ['Locations', 'Manage physical and virtual service locations.', 'Care network'],
  ['Operating hours', 'Define weekly hours, overnight periods and exceptions.', 'Care network'],
  ['Service catalogue', 'Govern services available to the network.', 'Services'],
  ['Facility services', 'Assign active services to facilities and locations.', 'Services'],
  ['Identifier schemes', 'Version prefix, pattern and sequence rules.', 'Configuration'],
  ['Administrator access', 'Invite and scope administrators safely.', 'Administration'],
  [
    'Review and activate',
    'Validate, submit and independently activate configuration.',
    'Configuration',
  ],
  ['Configuration history', 'Compare effective configuration versions.', 'Governance'],
  ['Audit log', 'Inspect immutable security and governance evidence.', 'Governance'],
];

const m2: Array<[string, string, string]> = [
  ['Workforce dashboard', 'Review workforce readiness, credentials and exceptions.', 'Overview'],
  ['Workforce directory', 'Search and filter persisted workforce records.', 'Directory'],
  ['Add workforce member', 'Start a clinical or non-clinical onboarding pathway.', 'Onboarding'],
  [
    'Duplicate and person search',
    'Match an existing person before creating a record.',
    'Onboarding',
  ],
  [
    'Personal and contact information',
    'Capture governed identity and contact details.',
    'Onboarding',
  ],
  ['Engagement and employment details', 'Create an effective-dated engagement.', 'Onboarding'],
  [
    'Practitioner profile',
    'Define profession without granting access or eligibility.',
    'Clinical profile',
  ],
  ['Qualifications', 'Create, verify and supersede qualifications.', 'Credentialing'],
  [
    'Professional registrations and licences',
    'Manage renewal, suspension and revocation history.',
    'Credentialing',
  ],
  [
    'Credential document upload',
    'Upload evidence into private quarantine and scanning.',
    'Credentialing',
  ],
  [
    'Credential verification queue',
    'Prioritize submitted credentials by SLA and risk.',
    'Credentialing',
  ],
  [
    'Credential review detail',
    'Review clean evidence and record an independent decision.',
    'Credentialing',
  ],
  ['Specialties', 'Maintain effective primary and secondary specialties.', 'Clinical profile'],
  [
    'Scope of practice',
    'Version and independently approve clinical boundaries.',
    'Clinical governance',
  ],
  [
    'Organization, facility, department and location assignments',
    'Create validated effective assignments.',
    'Assignments',
  ],
  [
    'Practitioner service assignments',
    'Assign eligible services in facility context.',
    'Assignments',
  ],
  [
    'Application roles and permissions',
    'Grant governed software access without clinical eligibility.',
    'Access',
  ],
  [
    'Availability and working pattern',
    'Save weekly availability as one governed batch.',
    'Scheduling',
  ],
  ['Invitation and account access', 'Link a user or send an expiring invitation.', 'Access'],
  ['Review and activate', 'Validate readiness and independently activate workforce.', 'Activation'],
  ['Workforce member profile', 'View the canonical selected-member record.', 'Directory'],
  ['Edit and transfer assignment', 'Transfer assignments with impact review.', 'Lifecycle'],
  [
    'Suspend and reactivate',
    'Control lifecycle transitions with reason and evidence.',
    'Lifecycle',
  ],
  ['Offboarding', 'End access and assignments while preserving attribution.', 'Lifecycle'],
  ['Expiring credentials dashboard', 'Monitor expiry risk and escalation.', 'Governance'],
  ['Workforce configuration history', 'Compare workforce configuration changes.', 'Governance'],
  ['Workforce audit log', 'Filter and export purpose-bound audit evidence.', 'Governance'],
  [
    'Controlled registries',
    'Govern catalogues while reusing the RBAC source of truth.',
    'Governance',
  ],
  ['Lifecycle and evidence timeline', 'Correlate lifecycle, decisions and evidence.', 'Governance'],
];

const m3: Array<[string, string, string]> = [
  [
    'Patient registry dashboard',
    'Review organization-local patient activity, registration and duplicate work.',
    'Overview',
  ],
  [
    'Patient directory',
    'Search minimum-necessary organization-local patient records.',
    'Directory',
  ],
  [
    'Start patient registration',
    'Create an expiring governed registration run before collecting patient data.',
    'Registration',
  ],
  [
    'Duplicate search',
    'Search before create and record an explicit duplicate disposition.',
    'Registration',
  ],
  [
    'Identity and demographics',
    'Maintain patient identity with partial-date and provenance semantics.',
    'Patient record',
  ],
  [
    'Contacts and addresses',
    'Maintain protected contact and address records without inferring consent.',
    'Patient record',
  ],
  [
    'Communication preferences',
    'Record preferences independently from consent and provider availability.',
    'Patient record',
  ],
  [
    'Patient identifiers',
    'Review masked identifiers; activation remains closed until a local scheme is approved.',
    'Identity governance',
  ],
  [
    'Caregivers and proxies',
    'Record relationship facts separately from proxy authority and portal linkage.',
    'Identity governance',
  ],
  [
    'Consent and privacy',
    'Review directives and restrictions without treating consent as a universal legal basis.',
    'Privacy and safety',
  ],
  [
    'Clinical safety flags',
    'Review concise governed flags; detailed clinical records remain separate.',
    'Privacy and safety',
  ],
  [
    'Review and register',
    'Validate the exact registration revision and complete it atomically.',
    'Registration',
  ],
  [
    'Patient summary',
    'Review the canonical minimum-necessary patient record and lifecycle.',
    'Directory',
  ],
  [
    'Duplicate review queue',
    'Claim and disposition explainable organization-local duplicate candidates.',
    'Duplicate governance',
  ],
  [
    'Merge review',
    'Request, independently decide and execute an exact impact-bound merge.',
    'Duplicate governance',
  ],
  [
    'Identity and audit timeline',
    'Review allow-listed patient identity evidence without raw audit payloads.',
    'Governance',
  ],
];

const m4: Array<[string, string, string]> = [
  [
    'Scheduling dashboard',
    'Review appointment activity, expiring holds and waitlist work.',
    'Overview',
  ],
  ['Calendar', 'Manage internal schedules and exact UTC appointment slots.', 'Scheduling'],
  [
    'Appointment directory',
    'Search bounded minimum-necessary appointment records.',
    'Appointments',
  ],
  ['New appointment', 'Start a staff-authorized expiring appointment request.', 'Booking workflow'],
  [
    'Patient selection',
    'Review or replace the selected patient before a slot is held.',
    'Booking workflow',
  ],
  [
    'Service, facility and location',
    'Select an active service context for the appointment request.',
    'Booking workflow',
  ],
  [
    'Eligible clinician selection',
    'Select a practitioner; eligibility is re-evaluated for the final slot instant.',
    'Booking workflow',
  ],
  ['Slot selection', 'Atomically acquire a five-minute internal slot hold.', 'Booking workflow'],
  [
    'Appointment review',
    'Review the exact patient, service, clinician, slot and hold expiry.',
    'Booking workflow',
  ],
  [
    'Payment requirement',
    'Review non-financial payment state; charging is deferred to Module 11.',
    'Booking workflow',
  ],
  [
    'Confirmation',
    'Re-evaluate eligibility and consume the held slot atomically.',
    'Booking workflow',
  ],
  [
    'Reschedule',
    'Move a confirmed appointment while retaining immutable prior-slot evidence.',
    'Lifecycle',
  ],
  [
    'Cancel or no-show',
    'Record an operational outcome without inventing a fee or refund decision.',
    'Lifecycle',
  ],
  ['Waitlist', 'Manage internal waitlist requests without sending unapproved offers.', 'Waitlist'],
  [
    'Appointment timeline',
    'Review allow-listed lifecycle evidence without raw audit/provider payloads.',
    'Governance',
  ],
];

const m5: Array<[string, string, string]> = [
  [
    'Encounter dashboard',
    'Review active encounters, unresolved red flags and unsigned clinical work.',
    'Overview',
  ],
  [
    'Open encounter',
    'Create an explicit episode and planned encounter from exact patient and care context.',
    'Encounter workflow',
  ],
  [
    'Patient and appointment context',
    'Review exact patient, appointment and encounter lifecycle context.',
    'Encounter workflow',
  ],
  [
    'Participants',
    'Manage immutable identity, role, assignment and eligibility snapshots.',
    'Encounter workflow',
  ],
  [
    'Presenting concerns',
    'Append attributed presenting concerns and create visible red-flag escalation when required.',
    'Clinical record',
  ],
  [
    'Clinical timeline',
    'Review minimum-necessary correlated clinical activity in encounter order.',
    'Clinical record',
  ],
  [
    'Problems and diagnoses',
    'Append explicitly coded or text-only problems and diagnoses.',
    'Clinical record',
  ],
  [
    'Orders and tasks',
    'Manage internal orders, attributed tasks and explicit red-flag acknowledgement.',
    'Clinical record',
  ],
  ['Encounter notes', 'Create append-only, digest-bound draft note versions.', 'Documentation'],
  [
    'Review and sign',
    'Sign the exact current note version using current practitioner identity and eligibility.',
    'Documentation',
  ],
  [
    'Amendment',
    'Append a signed correction linked to the exact signed note version.',
    'Documentation',
  ],
  [
    'Encounter history',
    'Review allow-listed lifecycle, signature and amendment evidence without raw payloads.',
    'Governance',
  ],
];

const cosTitles = [
  'Consultation context',
  'Patient story',
  'Presenting concerns',
  'Clinical timeline',
  'Medication review',
  'Allergies and safety',
  'Investigations',
  'Vital signs',
  'Clinical examination',
  'Red-flag assessment',
  'Problem list',
  'Differential assessment',
  'ROOT360 overview',
  'PhysioCore assessment',
  'Mind and narrative',
  'Lifestyle and environment',
  'Integrative evidence review',
  'Clinical synthesis',
  'Priorities and goals',
  'Coordinated care plan',
  'Intervention safety',
  'Consent and shared decision',
  'Document review',
  'AI-assisted synthesis',
  'Clinician review and approval',
  'Monitoring and follow-up',
  'Confirm and close',
] as const;

const build = (module: ModuleKey, source: Array<[string, string, string]>): Screen[] =>
  source.map(([title, purpose, group], index) => ({
    id: `${module}-${String(index + 1).padStart(2, '0')}`,
    module,
    title,
    purpose,
    group,
  }));

export const screens: Screen[] = [
  ...build('M1', m1),
  ...build('M2', m2),
  ...build('M3', m3).map((screen) => ({ ...screen, id: screen.id.replace(/^M3-/, 'P3-') })),
  ...build('M4', m4).map((screen) => ({ ...screen, id: screen.id.replace(/^M4-/, 'P4-') })),
  ...build('M5', m5).map((screen) => ({ ...screen, id: screen.id.replace(/^M5-/, 'P5-') })),
  ...cosTitles.map((title, index) => ({
    id: `COS-${String(index + 1).padStart(2, '0')}`,
    module: 'COS' as const,
    title,
    purpose: 'Clinician-in-the-loop assessment and coordinated-care workflow.',
    group:
      index < 12 ? 'Clinical assessment' : index < 20 ? 'Clinical synthesis' : 'Plan and follow-up',
  })),
];

export const findScreen = (id: string): Screen => {
  const screen = screens.find((candidate) => candidate.id === id);
  if (!screen) {
    throw new Error(`The CareOS screen registry does not contain ${id}.`);
  }
  return screen;
};
