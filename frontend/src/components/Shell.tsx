import { ChevronDown, LogOut, Menu, Search, X } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { screens, type ModuleKey } from '../data/screens';

type ShellProps = { currentId: string; children: ReactNode };

const workspaceLabels: Record<ModuleKey, string> = {
  M1: 'Administration',
  M2: 'Workforce',
  COS: 'Clinician workspace',
};

export function Shell({ currentId, children }: ShellProps) {
  const current = screens.find((screen) => screen.id === currentId) ?? screens[0];
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const moduleScreens = useMemo(
    () =>
      screens.filter(
        (screen) =>
          screen.module === current.module &&
          `${screen.id} ${screen.title}`.toLowerCase().includes(query.toLowerCase()),
      ),
    [current.module, query],
  );
  const groups = [...new Set(moduleScreens.map((screen) => screen.group))];

  return (
    <div className="app-shell">
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
          <span>CareOS · {workspaceLabels[current.module]}</span>
        </a>
        <div className="topbar-actions">
          <a
            className="workspace-switch"
            href={
              current.module === 'M1' ? '#/M2-01' : current.module === 'M2' ? '#/COS-01' : '#/M1-05'
            }
          >
            Switch workspace <ChevronDown size={16} />
          </a>
          <span className="user-label">Dr Prashant Gupta</span>
          <button className="icon-button" aria-label="Sign out">
            <LogOut size={20} />
          </button>
        </div>
      </header>
      <aside
        className={`sidebar ${open ? 'is-open' : ''}`}
        aria-label={`${workspaceLabels[current.module]} navigation`}
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
          {(['M1', 'M2', 'COS'] as const).map((module) => (
            <a
              key={module}
              className={current.module === module ? 'active' : ''}
              href={`#/${module}-01`}
            >
              {module}
            </a>
          ))}
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
                    className={screen.id === current.id ? 'active' : ''}
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
      <main className="content" id="main-content">
        {children}
      </main>
    </div>
  );
}
