import type { ModuleKey } from './screens';

export type WorkspaceDefinition = {
  description: string;
  href: string;
  key: ModuleKey;
  label: string;
};

export const workspaces: WorkspaceDefinition[] = [
  {
    key: 'M1',
    label: 'Administration',
    href: '#/M1-05',
    description: 'Organization setup, access, configuration and audit',
  },
  {
    key: 'M2',
    label: 'Workforce',
    href: '#/M2-01',
    description: 'Staff, credentials, assignments and availability',
  },
  {
    key: 'M3',
    label: 'Patients',
    href: '#/P3-01',
    description: 'Registration, identity and patient records',
  },
  {
    key: 'M4',
    label: 'Appointments',
    href: '#/P4-01',
    description: 'Scheduling, waitlists and confirmations',
  },
  {
    key: 'M5',
    label: 'Encounters',
    href: '#/P5-01',
    description: 'Visits, clinical notes and encounter review',
  },
  {
    key: 'COS',
    label: 'Clinical',
    href: '#/COS-01',
    description: 'Assessments, observations and clinical decisions',
  },
  {
    key: 'M7',
    label: 'Documents',
    href: '#/P7-01',
    description: 'Results, documents and governed sharing',
  },
  {
    key: 'M8',
    label: 'AI assistance',
    href: '#/P8-01',
    description: 'Reviewed, evidence-linked AI workflows',
  },
  {
    key: 'M9',
    label: 'Care plans',
    href: '#/P9-01',
    description: 'Goals, tasks and coordinated plans',
  },
  {
    key: 'M10',
    label: 'Follow-up',
    href: '#/P10-01',
    description: 'Outreach, escalations and outcomes',
  },
  {
    key: 'M11',
    label: 'Billing',
    href: '#/P11-01',
    description: 'Invoices, payments and financial review',
  },
  {
    key: 'M12',
    label: 'Reports',
    href: '#/P12-01',
    description: 'Operational and governed reporting',
  },
  {
    key: 'M13',
    label: 'Integrations',
    href: '#/P13-01',
    description: 'FHIR, interfaces and delivery monitoring',
  },
];

export function workspaceLabel(key: ModuleKey): string {
  return workspaces.find((workspace) => workspace.key === key)?.label ?? 'CareOS';
}
