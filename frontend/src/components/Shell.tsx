import { LogOut, Menu, Search, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { screens, type ModuleKey } from '../data/screens';

export type ShellSessionProps = {
  actorDisplayName: string;
  organizations: Array<{ id: string; label: string }>;
  onLogout(): Promise<void>;
  onSelectOrganization(organizationId: string): Promise<void>;
  selectedOrganizationId: string;
  sessionBusy: boolean;
  sessionNotice?: ReactNode;
  signingOut: boolean;
};

type ShellProps = ShellSessionProps & { currentId?: string; children: ReactNode };

const workspaceLabels: Record<ModuleKey, string> = {
  M1: 'Administration',
  M2: 'Workforce',
  M3: 'Patients',
  M4: 'Appointments',
  M5: 'Encounters',
  COS: 'Clinician workspace',
};

const workspaces: Array<{ key: ModuleKey; href: string }> = [
  { key: 'M1', href: '#/M1-01' },
  { key: 'M2', href: '#/M2-01' },
  { key: 'M3', href: '#/P3-01' },
  { key: 'M4', href: '#/P4-01' },
  { key: 'M5', href: '#/P5-01' },
  { key: 'COS', href: '#/COS-01' },
];

export function Shell({
  actorDisplayName,
  children,
  currentId,
  onLogout,
  onSelectOrganization,
  organizations,
  selectedOrganizationId,
  sessionBusy,
  sessionNotice,
  signingOut,
}: ShellProps) {
  const current = screens.find((screen) => screen.id === currentId);
  const currentModule = current?.module ?? 'M1';
  const main = useRef<HTMLElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const moduleScreens = useMemo(
    () =>
      screens.filter(
        (screen) =>
          screen.module === currentModule &&
          `${screen.id} ${screen.title}`.toLowerCase().includes(query.toLowerCase()),
      ),
    [currentModule, query],
  );
  const groups = [...new Set(moduleScreens.map((screen) => screen.group))];

  useEffect(() => {
    if (
      document.activeElement !== document.body &&
      document.activeElement !== main.current &&
      main.current?.contains(document.activeElement)
    ) {
      return;
    }
    const heading = main.current?.querySelector<HTMLHeadingElement>('h1');
    if (!heading) return;
    heading.tabIndex = -1;
    heading.focus();
  }, [currentId]);

  return (
    <div className="app-shell">
      <a
        className="skip-link"
        href="#main-content"
        onClick={(event) => {
          event.preventDefault();
          main.current?.focus();
        }}
      >
        Skip to main content
      </a>
      <header className="topbar">
        <button
          className="icon-button mobile-menu"
          aria-label="Open navigation"
          onClick={() => setOpen(true)}
        >
          <Menu />
        </button>
        <a className="brand" href="#/M1-05" aria-label="ROOTOPATHY CareOS home">
          <strong>ROOTOPATHY</strong>
          <span>CareOS · {workspaceLabels[currentModule]}</span>
        </a>
        <div className="topbar-actions">
          <label className="organization-switch">
            <span className="sr-only">Current organization</span>
            <select
              aria-label="Current organization"
              value={selectedOrganizationId}
              disabled={sessionBusy}
              onChange={(event) => void onSelectOrganization(event.target.value)}
            >
              {organizations.map((organization) => (
                <option key={organization.id} value={organization.id}>
                  {organization.label}
                </option>
              ))}
            </select>
          </label>
          <span className="user-label">{actorDisplayName}</span>
          <button
            className="icon-button"
            aria-label="Sign out"
            aria-busy={signingOut}
            disabled={sessionBusy}
            onClick={() => void onLogout()}
          >
            <LogOut size={20} />
          </button>
        </div>
      </header>
      <aside
        className={`sidebar ${open ? 'is-open' : ''}`}
        aria-label={`${workspaceLabels[currentModule]} navigation`}
      >
        <div className="sidebar-mobile-head">
          <span>Navigation</span>
          <button
            className="icon-button"
            aria-label="Close navigation"
            onClick={() => setOpen(false)}
          >
            <X />
          </button>
        </div>
        <label className="search-box">
          <Search size={17} />
          <span className="sr-only">Search screens</span>
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search screens"
          />
        </label>
        <div className="module-tabs" role="navigation" aria-label="Workspace selector">
          {workspaces.map((workspace) => (
            <a
              key={workspace.key}
              className={currentModule === workspace.key ? 'active' : ''}
              href={workspace.href}
            >
              {workspace.key}
            </a>
          ))}
        </div>
        <div className="mobile-session-controls">
          <strong>{actorDisplayName}</strong>
          <label>
            Organization
            <select
              value={selectedOrganizationId}
              disabled={sessionBusy}
              onChange={(event) => void onSelectOrganization(event.target.value)}
            >
              {organizations.map((organization) => (
                <option key={organization.id} value={organization.id}>
                  {organization.label}
                </option>
              ))}
            </select>
          </label>
          <button
            className="secondary-button"
            aria-label="Sign out from mobile navigation"
            disabled={sessionBusy}
            onClick={() => void onLogout()}
          >
            <LogOut size={17} /> Sign out
          </button>
        </div>
        <nav className="screen-nav">
          {groups.map((group) => (
            <section key={group}>
              <h2>{group}</h2>
              {moduleScreens
                .filter((screen) => screen.group === group)
                .map((screen) => (
                  <a
                    key={screen.id}
                    href={`#/${screen.id}`}
                    className={screen.id === current?.id ? 'active' : ''}
                    onClick={() => setOpen(false)}
                  >
                    <span>{screen.id}</span>
                    {screen.title}
                  </a>
                ))}
            </section>
          ))}
        </nav>
      </aside>
      {open && (
        <button
          className="scrim"
          aria-label="Close navigation overlay"
          onClick={() => setOpen(false)}
        />
      )}
      <main className="content" id="main-content" ref={main} tabIndex={-1}>
        {sessionNotice}
        {children}
      </main>
    </div>
  );
}
