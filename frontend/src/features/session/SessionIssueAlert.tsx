import { AlertTriangle, X } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { SessionIssue } from './session-types';

type SessionIssueAlertProps = {
  issue: SessionIssue;
  onDismiss?: () => void;
};

export function SessionIssueAlert({ issue, onDismiss }: SessionIssueAlertProps) {
  const alert = useRef<HTMLDivElement>(null);

  useEffect(() => {
    alert.current?.focus();
  }, [issue]);

  return (
    <div className="alert error-alert" role="alert" tabIndex={-1} ref={alert}>
      <AlertTriangle aria-hidden="true" size={20} />
      <div>
        <strong>{issue.title}</strong>
        <span>{issue.detail}</span>
        {issue.retryAfterSeconds !== undefined && (
          <small>Try again in about {issue.retryAfterSeconds} seconds.</small>
        )}
        {issue.correlationId !== 'unavailable' && (
          <small>Support reference: {issue.correlationId}</small>
        )}
      </div>
      {onDismiss && (
        <button
          type="button"
          className="icon-button"
          aria-label="Dismiss error"
          onClick={onDismiss}
        >
          <X size={18} />
        </button>
      )}
    </div>
  );
}
