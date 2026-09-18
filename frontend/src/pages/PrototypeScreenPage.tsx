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
import { Shell, type ShellSessionProps } from '../components/Shell';

const listIds = new Set([
  'M1-12',
  'M1-14',
  'M1-15',
  'M1-17',
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
const prototypeActionDescriptionId = 'prototype-action-description';

function UnavailableAction({
  className,
  label,
}: {
  className: 'primary-button' | 'secondary-button' | 'text-action';
  label: string;
}) {
  return (
    <button
      type="button"
      className={className}
      disabled
      aria-describedby={prototypeActionDescriptionId}
      aria-label={`${label} — unavailable in the synthetic prototype`}
      title="Unavailable until the production workflow is approved and server-backed"
    >
      {label}
    </button>
  );
}

function PrototypeBoundary() {
  return (
    <aside className="prototype-boundary" aria-labelledby="prototype-boundary-title">
      <CircleAlert aria-hidden="true" size={20} />
      <div>
        <strong id="prototype-boundary-title">Synthetic prototype only</strong>
        <p id={prototypeActionDescriptionId}>
          Records and values on this screen are illustrative. Production review, open, save and
          confirmation actions are unavailable until their approved server workflows exist. Do not
          enter real personal or clinical information.
        </p>
      </div>
    </aside>
  );
}

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
        <StatCard label="Ready" value="12" note="Synthetic sample" />
        <StatCard label="Needs review" value="3" note="Synthetic sample" />
        <StatCard label="In progress" value="7" note="Synthetic sample" />
        <StatCard label="Overdue" value="1" note="Synthetic sample" />
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>{screen.module === 'COS' ? 'Consultation readiness' : 'Setup readiness'}</h2>
            <p>This illustrative readiness layout does not load production state or evidence.</p>
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
                  {index === 3 ? 'Illustrative pending state' : 'Illustrative complete state'}
                </small>
              </span>
              <UnavailableAction className="text-action" label="Review" />
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
      ? [
          'Synthetic practitioner A',
          'Synthetic practitioner B',
          'Synthetic workforce record C',
          'Synthetic workforce record D',
        ]
      : [
          'Synthetic facility A',
          'Synthetic facility B',
          'Synthetic governance group',
          'Synthetic configuration',
        ];
  const records = names.map((name, index) => ({
    name,
    scope: index % 2 === 0 ? 'greater-noida' : 'network',
    status: index === 2 ? 'review' : 'active',
    type: index % 2 ? 'Secondary' : 'Primary',
    updated: `${index + 2} days ago`,
  }));
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('all');
  const [scope, setScope] = useState('all');
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const filteredRecords = records.filter(
    (record) =>
      (!normalizedQuery || record.name.toLocaleLowerCase().includes(normalizedQuery)) &&
      (status === 'all' || record.status === status) &&
      (scope === 'all' || record.scope === scope),
  );
  const hasFilters = query !== '' || status !== 'all' || scope !== 'all';

  return (
    <section className="panel">
      <div className="filter-grid">
        <label>
          Search
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={`Search ${screen.title.toLowerCase()}`}
          />
        </label>
        <label>
          Status
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="all">All statuses</option>
            <option value="active">Active</option>
            <option value="review">Review</option>
          </select>
        </label>
        <label>
          Scope
          <select value={scope} onChange={(event) => setScope(event.target.value)}>
            <option value="all">All locations</option>
            <option value="greater-noida">Greater Noida</option>
          </select>
        </label>
        <button
          type="button"
          className="secondary-button"
          disabled={!hasFilters}
          onClick={() => {
            setQuery('');
            setStatus('all');
            setScope('all');
          }}
        >
          Clear filters
        </button>
      </div>
      <p className="filter-summary" aria-live="polite">
        Showing {filteredRecords.length} of {records.length} synthetic records
      </p>
      <div className="table-wrap" role="region" aria-label={`${screen.title} records`} tabIndex={0}>
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
            {filteredRecords.map((record) => (
              <tr key={record.name}>
                <td>
                  <strong>{record.name}</strong>
                </td>
                <td>{record.type}</td>
                <td>
                  <span className={`badge ${record.status === 'review' ? 'warning' : 'success'}`}>
                    {record.status === 'review' ? 'Review' : 'Active'}
                  </span>
                </td>
                <td>{record.updated}</td>
                <td>
                  <UnavailableAction className="text-action" label="Open" />
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
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>{screen.title}</h2>
          <p>Changes are tenant-scoped, revision controlled and audited.</p>
        </div>
        <span className="badge neutral">Draft</span>
      </div>
      <div aria-label="Read-only synthetic form preview">
        <div className="form-grid">
          <label>
            Record name
            <input
              readOnly
              aria-describedby={prototypeActionDescriptionId}
              defaultValue={
                screen.module === 'M2'
                  ? 'Synthetic practitioner record'
                  : 'Synthetic organization record'
              }
            />
          </label>
          <label>
            Type
            <select disabled aria-describedby={prototypeActionDescriptionId} defaultValue="primary">
              <option value="primary">Primary</option>
              <option value="secondary">Secondary</option>
            </select>
          </label>
          <label>
            Effective from
            <input
              readOnly
              type="date"
              aria-describedby={prototypeActionDescriptionId}
              defaultValue="2026-09-13"
            />
          </label>
          <label>
            Status
            <select disabled aria-describedby={prototypeActionDescriptionId} defaultValue="draft">
              <option value="draft">Draft</option>
              <option value="review">Ready for review</option>
            </select>
          </label>
          <label className="full-width">
            Reason for change
            <textarea
              readOnly
              aria-describedby={prototypeActionDescriptionId}
              defaultValue="Initial synthetic prototype record"
            />
          </label>
        </div>
        <div className="form-actions">
          <UnavailableAction className="secondary-button" label="Save draft" />
          <UnavailableAction className="primary-button" label="Save and continue" />
        </div>
      </div>
    </section>
  );
}

function ClinicalScreen({ screen }: { screen: Screen }) {
  return (
    <div className="clinical-layout">
      <section className="patient-strip">
        <div>
          <span>Patient</span>
          <strong>Synthetic patient</strong>
        </div>
        <div>
          <span>Encounter</span>
          <strong>Synthetic draft consultation</strong>
        </div>
        <div>
          <span>Responsible clinician</span>
          <strong>Synthetic clinician</strong>
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
          <textarea
            readOnly
            aria-describedby={prototypeActionDescriptionId}
            defaultValue="Synthetic prototype note — no real patient information."
          />
        </label>
        <div className="form-actions">
          <UnavailableAction className="secondary-button" label="Save draft" />
          <UnavailableAction className="primary-button" label="Confirm and continue" />
        </div>
      </section>
    </div>
  );
}

export function PrototypeScreenPage({ id, shell }: { id: string; shell: ShellSessionProps }) {
  const screen = findScreen(id);
  const index = screens.findIndex((item) => item.id === screen.id);
  const previous = screens[Math.max(0, index - 1)] ?? screen;
  const next = screens[Math.min(screens.length - 1, index + 1)] ?? screen;
  const body = useMemo(() => {
    if (screen.module === 'COS') return <ClinicalScreen screen={screen} />;
    if (dashboardIds.has(screen.id)) return <Dashboard screen={screen} />;
    if (listIds.has(screen.id)) return <DataList screen={screen} />;
    if (formIds.has(screen.id)) return <FormScreen screen={screen} />;
    return <Dashboard screen={screen} />;
  }, [screen]);

  return (
    <Shell currentId={screen.id} {...shell}>
      <div className="page-head">
        <div>
          <span className="eyebrow">{screen.id}</span>
          <h1>{screen.title}</h1>
          <p>{screen.purpose}</p>
        </div>
        <span className="badge prototype">
          <CircleAlert size={14} /> Synthetic prototype
        </span>
      </div>
      <PrototypeBoundary />
      {body}
      <nav className="page-pagination" aria-label="Prototype pagination">
        {index === 0 ? (
          <span className="pagination-link pagination-disabled" aria-disabled="true">
            <ArrowLeft /> {previous.id}
          </span>
        ) : (
          <a className="pagination-link" href={`#/${previous.id}`}>
            <ArrowLeft /> {previous.id}
          </a>
        )}
        <span className="pagination-status">
          {index + 1} of {screens.length}
        </span>
        {index === screens.length - 1 ? (
          <span
            className="pagination-link pagination-next pagination-disabled"
            aria-disabled="true"
          >
            {next.id} <ArrowRight />
          </span>
        ) : (
          <a className="pagination-link pagination-next" href={`#/${next.id}`}>
            {next.id} <ArrowRight />
          </a>
        )}
      </nav>
    </Shell>
  );
}
