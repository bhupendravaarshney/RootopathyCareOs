export type ModuleKey =
  'M1' | 'M2' | 'M3' | 'M4' | 'M5' | 'COS' | 'M7' | 'M8' | 'M9' | 'M10' | 'M11' | 'M12';

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

const m7: Array<[string, string, string]> = [
  [
    'Document dashboard',
    'Review document processing, results and acknowledgement safety.',
    'Overview',
  ],
  [
    'Patient document list',
    'Review minimum-necessary patient-linked document records.',
    'Documents',
  ],
  ['Upload document', 'Place an exact bounded file in private quarantine.', 'Documents'],
  [
    'Classification and metadata',
    'Append governed classification and retention metadata.',
    'Documents',
  ],
  ['Scan status', 'Review quarantine, scan and promotion evidence.', 'Security'],
  ['Document viewer', 'Open a clean version through purpose-bound short-lived access.', 'Security'],
  ['Result inbox', 'Review diagnostic reports and abnormal or critical state.', 'Results'],
  ['Result detail', 'Record source-preserving laboratory or imaging result evidence.', 'Results'],
  ['Acknowledge or escalate', 'Acknowledge, escalate and resolve result safety flags.', 'Results'],
  [
    'Version history',
    'Review immutable document version and classification lineage.',
    'Governance',
  ],
  [
    'Export or share intent',
    'Record purpose-bound export or share intent without claiming delivery.',
    'Governance',
  ],
];

const m8: Array<[string, string, string]> = [
  ['AI session launcher', 'Launch a draft AI session without invoking a model.', 'Session setup'],
  [
    'Purpose and consent check',
    'Record purpose, legal basis, consent and minimum-necessary confirmation.',
    'Session setup',
  ],
  ['Input selection', 'Approve exact source references, revisions and digests.', 'Inputs'],
  [
    'Transcription and extraction',
    'Request a versioned provider job through the fail-closed processing boundary.',
    'Processing',
  ],
  ['Draft summary', 'Review and append clinician edits to a visibly labeled AI draft.', 'Drafts'],
  [
    'Clinical suggestion panel',
    'Review suggestions without automatic clinical adoption.',
    'Drafts',
  ],
  [
    'Safety and uncertainty flags',
    'Review uncertainty, acknowledge safety flags and complete escalation.',
    'Safety',
  ],
  [
    'Source and provenance viewer',
    'Trace model, prompt and draft claims to approved input evidence.',
    'Governance',
  ],
  [
    'Clinician review and approval',
    'Explicitly accept or reject the exact latest draft version.',
    'Review',
  ],
  [
    'AI session history',
    'Review immutable session, provider, safety, usage and decision history.',
    'Governance',
  ],
];

const m9: Array<[string, string, string]> = [
  [
    'Care plan dashboard',
    'Review coordinated-plan readiness, ownership, safety and lifecycle state.',
    'Overview',
  ],
  [
    'Create coordinated plan',
    'Create a draft plan bound to one verified patient, encounter and responsible clinician.',
    'Plan construction',
  ],
  [
    'Problems and priorities',
    'Append sourced problems and explicit priorities to the current draft version.',
    'Plan construction',
  ],
  ['Goals', 'Append measurable clinical and patient-stated goals.', 'Plan construction'],
  [
    'Interventions',
    'Append complete interventions with rationale, timing, stop criteria and monitoring.',
    'Coordination',
  ],
  [
    'Modality coordination',
    'Review cross-modality sequencing, ownership and coordination without hiding uncertainty.',
    'Coordination',
  ],
  [
    'Owners and tasks',
    'Assign every intervention to an eligible owner and create an attributable clinical task.',
    'Coordination',
  ],
  [
    'Consent and preferences',
    'Append visible patient consent, preferences and communication needs.',
    'Safety and approval',
  ],
  [
    'Safety and interaction review',
    'Record an exact-version cross-modality interaction and safety review.',
    'Safety and approval',
  ],
  [
    'Clinician approval',
    'Freeze, approve and activate the exact complete plan version with accountable review.',
    'Safety and approval',
  ],
  [
    'Patient summary',
    'Present the current approved plan in bounded patient-facing language.',
    'Summary and lineage',
  ],
  [
    'Plan versions and amendments',
    'Review immutable versions and create a reason-bound successor amendment.',
    'Summary and lineage',
  ],
];

const m10: Array<[string, string, string]> = [
  [
    'Monitoring dashboard',
    'Review active monitoring plans, due events and unresolved escalations.',
    'Overview',
  ],
  [
    'Rules',
    'Define exact thresholds, severity, owner, task priority and acknowledgement target.',
    'Monitoring design',
  ],
  [
    'Domains',
    'Define version-frozen outcome domains, measures, units, direction and targets.',
    'Monitoring design',
  ],
  [
    'Measures',
    'Record an attributed value and evaluate every applicable threshold atomically.',
    'Outcome evidence',
  ],
  [
    'Escalation',
    'Acknowledge and resolve owned threshold breaches with explicit evidence.',
    'Outcome evidence',
  ],
  [
    'Follow-up schedule',
    'Schedule baseline and future monitoring events with accountable ownership.',
    'Monitoring design',
  ],
  [
    'Interpretation',
    'Append clinical interpretation and recommendation to an exact completed measurement.',
    'Clinical review',
  ],
  [
    'Confirm plan',
    'Freeze the complete monitoring definition, then confirm it with recent MFA.',
    'Clinical review',
  ],
  [
    'Outcome timeline',
    'Review immutable measurements, interpretations and escalation outcomes over time.',
    'Governance',
  ],
];

const m11: Array<[string, string, string]> = [
  [
    'Billing dashboard',
    'Review open invoices, settlement state, claims and reconciliation exceptions.',
    'Overview',
  ],
  ['Price books', 'Version, populate and activate currency-bound price books.', 'Pricing'],
  [
    'Packages',
    'Version packages and exact service entitlements against active pricing.',
    'Pricing',
  ],
  [
    'Estimate',
    'Create and freeze a patient-bound estimate from authoritative active pricing.',
    'Receivables',
  ],
  [
    'Invoice',
    'Issue immutable invoice lines from a valid finalized estimate without changing clinical state.',
    'Receivables',
  ],
  [
    'Payment',
    'Record non-card manual settlement evidence using exact invoice amount and currency.',
    'Settlement',
  ],
  [
    'Payment link',
    'Create a card-data-free payment intent while provider-hosted delivery remains fail closed.',
    'Settlement',
  ],
  [
    'Refund or adjustment',
    'Append authorized refunds or debit and credit adjustments without rewriting settlement history.',
    'Settlement',
  ],
  [
    'Claims',
    'Create invoice-bound claims, submit exact amounts and append remittance evidence.',
    'Claims',
  ],
  [
    'Reconciliation',
    'Snapshot expected and observed totals and explicitly resolve every variance.',
    'Governance',
  ],
  [
    'Financial audit or export',
    'Review financial evidence and request bounded purpose-specific exports.',
    'Governance',
  ],
];

const m12: Array<[string, string, string]> = [
  [
    'Reporting dashboard',
    'Review aggregate report coverage, active schedules and export exceptions.',
    'Overview',
  ],
  [
    'Operational reports',
    'Run bounded aggregate appointment and encounter reports without patient detail.',
    'Operations',
  ],
  [
    'Clinical safety reports',
    'Run bounded aggregate result-safety reports without result values or narratives.',
    'Clinical governance',
  ],
  [
    'Outcome reports',
    'Run bounded aggregate outcome and escalation reports without measurement values.',
    'Clinical governance',
  ],
  [
    'Workforce governance',
    'Run bounded aggregate workforce-readiness and credential-governance reports.',
    'Governance',
  ],
  [
    'Access and security reports',
    'Run bounded aggregate access and security evidence reports.',
    'Governance',
  ],
  [
    'AI governance',
    'Run bounded aggregate AI lifecycle and safety-governance reports.',
    'Governance',
  ],
  [
    'Financial reports',
    'Run bounded aggregate invoice and reconciliation reports without payment references.',
    'Finance',
  ],
  [
    'Scheduled exports',
    'Define governed schedules and request an expiring private export for one exact report run.',
    'Exports',
  ],
  [
    'Report audit and history',
    'Review attributable report-run, schedule and export history without source-record content.',
    'Governance',
  ],
];

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
  ...build('M7', m7).map((screen) => ({ ...screen, id: screen.id.replace(/^M7-/, 'P7-') })),
  ...build('M8', m8).map((screen) => ({ ...screen, id: screen.id.replace(/^M8-/, 'P8-') })),
  ...build('M9', m9).map((screen) => ({ ...screen, id: screen.id.replace(/^M9-/, 'P9-') })),
  ...build('M10', m10).map((screen) => ({ ...screen, id: screen.id.replace(/^M10-/, 'P10-') })),
  ...build('M11', m11).map((screen) => ({ ...screen, id: screen.id.replace(/^M11-/, 'P11-') })),
  ...build('M12', m12).map((screen) => ({ ...screen, id: screen.id.replace(/^M12-/, 'P12-') })),
];

export const findScreen = (id: string): Screen => {
  const screen = screens.find((candidate) => candidate.id === id);
  if (!screen) {
    throw new Error(`The CareOS screen registry does not contain ${id}.`);
  }
  return screen;
};
