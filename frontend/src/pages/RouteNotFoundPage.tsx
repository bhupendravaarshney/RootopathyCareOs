import { CircleAlert, House } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { Shell, type ShellSessionProps } from '../components/Shell';

export function RouteNotFoundPage({ shell }: { shell: ShellSessionProps }) {
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  return (
    <Shell {...shell}>
      <section className="panel route-not-found" aria-labelledby="route-not-found-heading">
        <span className="route-not-found-icon">
          <CircleAlert aria-hidden="true" />
        </span>
        <span className="eyebrow">Route unavailable</span>
        <h1 id="route-not-found-heading" ref={heading} tabIndex={-1}>
          Page not found
        </h1>
        <p>The requested CareOS page is not registered. No business screen was loaded.</p>
        <a className="primary-button button-link" href="#/M1-05">
          <House size={17} aria-hidden="true" /> Return to administration dashboard
        </a>
      </section>
    </Shell>
  );
}
