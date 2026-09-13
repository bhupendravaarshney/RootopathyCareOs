import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  Check,
  CircleAlert,
  FileCheck2,
  ShieldCheck,
  Sparkles,
  UserRound,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { findScreen, screens, type Screen } from '../data/screens';
import { Shell } from '../components/Shell';

const listIds = new Set([
  'M1-12',
  'M1-14',
  'M1-15',
  'M1-17',
  'M1-20',
  'M1-22',
  'M1-23',
  'M2-02',
  'M2-11',
  'M2-25',
  'M2-26',
  'M2-27',
  'M2-28',
]);
const formIds = new Set([
  'M1-07',
  'M1-08',
  'M1-09',
  'M1-10',
  'M1-11',
  'M1-13',
  'M1-16',
  'M1-18',
  'M1-19',
  'M2-03',
  'M2-05',
  'M2-06',
  'M2-07',
  'M2-08',
  'M2-09',
  'M2-10',
  'M2-13',
  'M2-14',
  'M2-15',
  'M2-16',
  'M2-17',
  'M2-18',
  'M2-19',
  'M2-22',
  'M2-23',
  'M2-24',
]);
const dashboardIds = new Set(['M1-05', 'M1-06', 'M1-21', 'M2-01', 'M2-20', 'M2-21', 'M2-29']);

function StatCard({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <article className="stat-card">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{note}</small>
    </article>
  );
}

function Dashboard({ screen }: { screen: Screen }) {
  return (
    <>
      <div className="stats-grid">
        <StatCard label="Ready" value="12" note="Server-calculated" />
        <StatCard label="Needs review" value="3" note="Action required" />
        <StatCard label="In progress" value="7" note="Saved drafts" />
        <StatCard label="Overdue" value="1" note="Escalated safely" />
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>{screen.module === 'COS' ? 'Consultation readiness' : 'Setup readiness'}</h2>
            <p>Each gate is independently calculated and linked to its source evidence.</p>
          </div>
          <span className="badge success">
            <Check size={14} /> 82% complete
          </span>
        </div>
        <div className="check-list">
          {[
            'Identity and context confirmed',
            'Required evidence recorded',
            'Safety and governance checked',
            'Independent approval',
          ].map((item, index) => (
            <div key={item}>
              <span className={`status-icon ${index === 3 ? 'warning' : ''}`}>
                {index === 3 ? <AlertTriangle size={17} /> : <Check size={17} />}
              </span>
              <span>
                <strong>{item}</strong>
                <small>
                  {index === 3
                    ? 'Awaiting authorized reviewer'
                    : 'Complete with traceable evidence'}
                </small>
              </span>
              <button className="text-action">Review</button>
            </div>
          ))}
        </div>
      </section>
    </>
  );
}

function DataList({ screen }: { screen: Screen }) {
  const names =
    screen.module === 'M2'
      ? ['Dr Ananya Mehra', 'Dr Karan Malhotra', 'Rhea Kapoor', 'Amit Singh']
      : ['ROOTOPATHY Greater Noida', 'Healing Lounge', 'Clinical Governance', 'Configuration v1.2'];
  return (
    <section className="panel">
      <div className="filter-grid">
        <label>
          Search
          <input placeholder={`Search ${screen.title.toLowerCase()}`} />
        </label>
        <label>
          Status
          <select defaultValue="all">
            <option value="all">All statuses</option>
            <option>Active</option>
            <option>Draft</option>
          </select>
        </label>
        <label>
          Scope
          <select defaultValue="all">
            <option value="all">All locations</option>
            <option>Greater Noida</option>
          </select>
        </label>
        <button className="secondary-button">Clear filters</button>
      </div>
      <div className="table-wrap">
        <table>
          <caption className="sr-only">{screen.title} records</caption>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Status</th>
              <th>Updated</th>
              <th>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {names.map((name, index) => (
              <tr key={name}>
                <td>
                  <a href={screen.module === 'M2' ? '#/M2-21' : '#/M1-07'}>{name}</a>
                </td>
                <td>{index % 2 ? 'Secondary' : 'Primary'}</td>
                <td>
                  <span className={`badge ${index === 2 ? 'warning' : 'success'}`}>
                    {index === 2 ? 'Review' : 'Active'}
                  </span>
                </td>
                <td>{index + 2} days ago</td>
                <td>
                  <button className="text-action">Open</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function FormScreen({ screen }: { screen: Screen }) {
  const [saved, setSaved] = useState(false);
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>{screen.title}</h2>
          <p>Changes are tenant-scoped, revision controlled and audited.</p>
        </div>
        <span className="badge neutral">Draft</span>
      </div>
      {saved && (
        <div className="alert success-alert" role="status">
          <Check size={18} /> Prototype interaction saved locally. Production persistence is a later
          module phase.
        </div>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          setSaved(true);
        }}
      >
        <div className="form-grid">
          <label>
            Record name
            <input
              required
              defaultValue={screen.module === 'M2' ? 'Dr Ananya Mehra' : 'ROOTOPATHY Care Network'}
            />
          </label>
          <label>
            Type
            <select defaultValue="primary">
              <option value="primary">Primary</option>
              <option value="secondary">Secondary</option>
            </select>
          </label>
          <label>
            Effective from
            <input type="date" defaultValue="2026-09-13" />
          </label>
          <label>
            Status
            <select defaultValue="draft">
              <option value="draft">Draft</option>
              <option value="review">Ready for review</option>
            </select>
          </label>
          <label className="full-width">
            Reason for change
            <textarea required defaultValue="Initial synthetic prototype record" />
          </label>
        </div>
        <div className="form-actions">
          <button type="button" className="secondary-button">
            Save draft
          </button>
          <button className="primary-button">Save and continue</button>
        </div>
      </form>
    </section>
  );
}

function AuthScreen({ screen }: { screen: Screen }) {
  return (
    <section className="auth-panel panel">
      <div className="auth-mark">
        <ShieldCheck />
      </div>
      <h2>{screen.title}</h2>
      <p>Secure access to the ROOTOPATHY CareOS workspace.</p>
      <form onSubmit={(event) => event.preventDefault()}>
        <label>
          Email address
          <input type="email" defaultValue="owner@rootopathy.test" />
        </label>
        <label>
          {screen.id === 'M1-03' ? 'Authentication code' : 'Password'}
          <input
            type={screen.id === 'M1-03' ? 'text' : 'password'}
            defaultValue={screen.id === 'M1-03' ? '123456' : 'CareOS-Local-Only'}
          />
        </label>
        <button className="primary-button full-button">Continue securely</button>
      </form>
      <small>Synthetic local demonstration only. Never use these credentials in production.</small>
    </section>
  );
}

function ClinicalScreen({ screen }: { screen: Screen }) {
  const [confirmed, setConfirmed] = useState(false);
  return (
    <div className="clinical-layout">
      <section className="patient-strip">
        <div>
          <span>Patient</span>
          <strong>Rahul Sharma</strong>
        </div>
        <div>
          <span>Encounter</span>
          <strong>Draft consultation</strong>
        </div>
        <div>
          <span>Responsible clinician</span>
          <strong>Dr Prashant Gupta</strong>
        </div>
      </section>
      <section className="panel clinical-panel">
        <div className="panel-heading">
          <div>
            <h2>{screen.title}</h2>
            <p>{screen.purpose}</p>
          </div>
          <span className="badge info">
            <Sparkles size={14} /> Clinician-in-loop
          </span>
        </div>
        <div className="clinical-grid">
          <article>
            <UserRound />
            <h3>Patient context</h3>
            <p>Structured facts remain connected to the patient’s complete story.</p>
          </article>
          <article>
            <FileCheck2 />
            <h3>Evidence</h3>
            <p>Sources, timestamps, authorship and confidence stay visible.</p>
          </article>
          <article>
            <ShieldCheck />
            <h3>Safety</h3>
            <p>Red flags and escalation rules are evaluated before progression.</p>
          </article>
        </div>
        <label className="full-width clinical-note">
          Clinical note
          <textarea defaultValue="Synthetic prototype note — no real patient information." />
        </label>
        <div className="form-actions">
          <button className="secondary-button">Save draft</button>
          <button className="primary-button" onClick={() => setConfirmed(true)}>
            {confirmed ? 'Confirmed' : 'Confirm and continue'}
          </button>
        </div>
      </section>
    </div>
  );
}

export function PrototypeScreenPage({ id }: { id: string }) {
  const screen = findScreen(id);
  const index = screens.findIndex((item) => item.id === screen.id);
  const previous = screens[Math.max(0, index - 1)];
  const next = screens[Math.min(screens.length - 1, index + 1)];
  const body = useMemo(() => {
    if (screen.module === 'COS') return <ClinicalScreen screen={screen} />;
    if (['M1-01', 'M1-02', 'M1-03', 'M1-04'].includes(screen.id))
      return <AuthScreen screen={screen} />;
    if (dashboardIds.has(screen.id)) return <Dashboard screen={screen} />;
    if (listIds.has(screen.id)) return <DataList screen={screen} />;
    if (formIds.has(screen.id)) return <FormScreen screen={screen} />;
    return <Dashboard screen={screen} />;
  }, [screen]);

  return (
    <Shell currentId={screen.id}>
      <div className="page-head">
        <div>
          <span className="eyebrow">{screen.id}</span>
          <h1>{screen.title}</h1>
          <p>{screen.purpose}</p>
        </div>
        <span className="badge prototype">
          <CircleAlert size={14} /> Clickable prototype
        </span>
      </div>
      {body}
      <nav className="page-pagination" aria-label="Prototype pagination">
        <a href={`#/${previous.id}`} aria-disabled={index === 0}>
          <ArrowLeft /> {previous.id}
        </a>
        <span>
          {index + 1} of {screens.length}
        </span>
        <a href={`#/${next.id}`} aria-disabled={index === screens.length - 1}>
          {next.id} <ArrowRight />
        </a>
      </nav>
    </Shell>
  );
}
