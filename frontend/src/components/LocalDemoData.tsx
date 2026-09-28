import { Database, FlaskConical } from 'lucide-react';
import { findScreen, type ModuleKey } from '../data/screens';

type DemoProfile = {
  columns: [string, string, string];
  rows: Array<[string, string, string]>;
};

const demoProfiles: Record<Exclude<ModuleKey, 'M1'>, DemoProfile> = {
  M2: {
    columns: ['Team member', 'Current workflow', 'State'],
    rows: [
      ['Dr Asha Demo', 'General practitioner', 'Active'],
      ['Nurse Rohan Demo', 'Credential renewal', 'Due in 21 days'],
      ['Mira Demo', 'Care coordinator onboarding', 'In review'],
    ],
  },
  M3: {
    columns: ['Patient', 'Care need', 'State'],
    rows: [
      ['Anaya Demo', 'New patient registration', 'Verified'],
      ['Kabir Demo', 'Identity review', 'Needs attention'],
      ['Diya Demo', 'Long-term care record', 'Active'],
    ],
  },
  M4: {
    columns: ['Patient', 'Appointment', 'Schedule'],
    rows: [
      ['Anaya Demo', 'Initial consultation', 'Today · 10:30'],
      ['Kabir Demo', 'Follow-up visit', 'Today · 14:00'],
      ['Diya Demo', 'Care-plan review', 'Tomorrow · 09:15'],
    ],
  },
  M5: {
    columns: ['Patient', 'Encounter', 'State'],
    rows: [
      ['Anaya Demo', 'Initial consultation', 'In progress'],
      ['Kabir Demo', 'Follow-up consultation', 'Ready to sign'],
      ['Diya Demo', 'Care-plan review', 'Completed'],
    ],
  },
  COS: {
    columns: ['Patient', 'Assessment', 'Result'],
    rows: [
      ['Anaya Demo', 'Clinical intake', 'Review complete'],
      ['Kabir Demo', 'Safety screening', 'Follow-up advised'],
      ['Diya Demo', 'Outcome assessment', 'Improving'],
    ],
  },
  M7: {
    columns: ['Patient', 'Document', 'State'],
    rows: [
      ['Anaya Demo', 'Consultation summary', 'Final'],
      ['Kabir Demo', 'Laboratory result', 'New result'],
      ['Diya Demo', 'Referral letter', 'Awaiting review'],
    ],
  },
  M8: {
    columns: ['Session', 'Assistant task', 'Review'],
    rows: [
      ['Demo session 104', 'Visit summary draft', 'Clinician review'],
      ['Demo session 103', 'Care-gap suggestions', 'Accepted with edits'],
      ['Demo session 102', 'Patient instructions', 'Approved'],
    ],
  },
  M9: {
    columns: ['Patient', 'Care plan', 'Progress'],
    rows: [
      ['Anaya Demo', 'Recovery and mobility', '3 of 5 goals on track'],
      ['Kabir Demo', 'Medication adherence', 'Review due'],
      ['Diya Demo', 'Long-term wellness', 'Active'],
    ],
  },
  M10: {
    columns: ['Patient', 'Follow-up', 'Outcome'],
    rows: [
      ['Anaya Demo', '48-hour check-in', 'Completed'],
      ['Kabir Demo', 'Symptom review', 'Escalated'],
      ['Diya Demo', 'Monthly outcome survey', 'Due tomorrow'],
    ],
  },
  M11: {
    columns: ['Account', 'Billing item', 'State'],
    rows: [
      ['Anaya Demo', 'Consultation invoice', 'Paid'],
      ['Kabir Demo', 'Follow-up invoice', 'Part paid'],
      ['Diya Demo', 'Care package', 'Draft'],
    ],
  },
  M12: {
    columns: ['Report', 'Coverage', 'Last run'],
    rows: [
      ['Daily operations', 'All facilities', 'Today · 07:00'],
      ['Credential risk', 'Clinical workforce', 'Yesterday'],
      ['Care outcomes', 'Active care plans', '2 days ago'],
    ],
  },
  M13: {
    columns: ['Connection', 'Resource', 'Health'],
    rows: [
      ['Demo FHIR endpoint', 'Patient and encounter', 'Healthy'],
      ['Demo laboratory feed', 'Diagnostic reports', 'Monitoring'],
      ['Demo notification channel', 'Care reminders', 'Healthy'],
    ],
  },
};

export function LocalDemoBanner() {
  return (
    <aside className="local-demo-banner" role="note">
      <Database aria-hidden="true" size={20} />
      <div>
        <strong>Local demo administrator</strong>
        <span>
          You can explore every workspace. Where live records do not exist yet, CareOS shows a
          clearly marked read-only preview.
        </span>
      </div>
      <a href="#/M1-05">Administrator overview</a>
    </aside>
  );
}

export function LocalDemoData({ screenId }: { screenId: string }) {
  const screen = findScreen(screenId);
  if (screen.module === 'M1') return null;
  const profile = demoProfiles[screen.module];
  const headingId = `local-demo-${screenId.toLowerCase()}`;

  return (
    <section aria-labelledby={headingId} className="local-demo-data">
      <header>
        <div>
          <span className="badge prototype">
            <FlaskConical aria-hidden="true" size={14} /> Local demo data
          </span>
          <h3 id={headingId}>Example records for {screen.title}</h3>
          <p>The live dataset is empty, so these examples show how this workspace will look.</p>
        </div>
        <span className="badge neutral">Read-only preview</span>
      </header>
      <div
        className="table-wrap"
        role="region"
        aria-label={`${screen.title} demo records`}
        tabIndex={0}
      >
        <table className="local-demo-table">
          <thead>
            <tr>
              {profile.columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {profile.rows.map((row) => (
              <tr key={row.join(':')}>
                {row.map((value, index) => (
                  <td data-label={profile.columns[index]} key={profile.columns[index]}>
                    {value}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="local-demo-disclaimer">
        Illustrative local-only content. It is not stored in the database and cannot be used for
        clinical or operational decisions.
      </p>
    </section>
  );
}
