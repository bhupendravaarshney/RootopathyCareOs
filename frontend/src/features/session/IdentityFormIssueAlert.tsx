import { AlertTriangle } from 'lucide-react';
import { useEffect, useRef } from 'react';

type IdentityFormIssueAlertProps = {
  fieldId?: string;
  message: string;
  title?: string;
};

export function IdentityFormIssueAlert({
  fieldId,
  message,
  title = 'Check the highlighted field',
}: IdentityFormIssueAlertProps) {
  const alert = useRef<HTMLDivElement>(null);
  const messageId = fieldId ? `${fieldId}-error` : undefined;

  useEffect(() => {
    alert.current?.focus();
  }, [fieldId, message]);

  return (
    <div className="alert error-alert" role="alert" tabIndex={-1} ref={alert}>
      <AlertTriangle aria-hidden="true" size={20} />
      <div>
        <strong>{title}</strong>
        {fieldId ? (
          <a
            className="error-field-link"
            href={`#${fieldId}`}
            onClick={(event) => {
              event.preventDefault();
              document.getElementById(fieldId)?.focus();
            }}
          >
            <span id={messageId}>{message}</span>
          </a>
        ) : (
          <span>{message}</span>
        )}
      </div>
    </div>
  );
}
