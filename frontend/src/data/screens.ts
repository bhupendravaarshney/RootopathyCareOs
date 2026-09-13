export type ModuleKey = 'M1' | 'M2' | 'COS';

export type Screen = {
  id: string;
  module: ModuleKey;
  title: string;
  purpose: string;
  group: string;
};

const m1: Array<[string, string, string]> = [
  ['Login', 'Authenticate securely and continue to the correct workspace.', 'Identity'],
  ['Invitation', 'Accept a one-use administrator invitation.', 'Identity'],
  ['MFA', 'Enroll or confirm multi-factor authentication.', 'Identity'],
  ['Organization selector', 'Choose an authorized organization workspace.', 'Identity'],
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
  ...cosTitles.map((title, index) => ({
    id: `COS-${String(index + 1).padStart(2, '0')}`,
    module: 'COS' as const,
    title,
    purpose: 'Clinician-in-the-loop assessment and coordinated-care workflow.',
    group:
      index < 12 ? 'Clinical assessment' : index < 20 ? 'Clinical synthesis' : 'Plan and follow-up',
  })),
];

export const findScreen = (id: string) => screens.find((screen) => screen.id === id) ?? screens[4];
