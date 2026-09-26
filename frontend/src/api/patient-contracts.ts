import type {
  PatientRegistryImpactPreviewResponse,
  PatientRegistryRow,
  PatientRegistryScreen,
  WorkforceAction,
  WorkforceColumn,
  WorkforceField,
  WorkforceImpactItem,
  WorkforceMetric,
  WorkforceNotice,
  WorkforceOption,
} from './generated';

export type { PatientRegistryImpactPreviewResponse } from './generated';

export type PatientResponseValidator<T> = (value: unknown) => value is T;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const screenPattern = /^P3-(0[1-9]|1[0-6])$/;
const actionKeyPattern = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const fieldKeyPattern = /^[A-Za-z][A-Za-z0-9]{0,79}$/;
const patientLinkPattern = /^#\/P3-(0[1-9]|1[0-6])$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const fieldTypes = new Set(['date', 'datetime-local', 'hidden', 'select', 'text', 'uuid']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(
  value: unknown,
  required: readonly string[],
  optional: readonly string[] = [],
): value is Record<string, unknown> {
  if (!isRecord(value)) return false;
  const permitted = new Set([...required, ...optional]);
  const keys = Object.keys(value);
  return required.every((key) => keys.includes(key)) && keys.every((key) => permitted.has(key));
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isOptionalNullableUuid(value: unknown): value is string | null | undefined {
  return value === undefined || value === null || isUuid(value);
}

function isDateTime(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function isArrayOf<T>(value: unknown, validator: (item: unknown) => item is T): value is T[] {
  return Array.isArray(value) && value.every(validator);
}

function hasUniqueValues(values: readonly string[]) {
  return new Set(values).size === values.length;
}

function isStringMap(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === 'string');
}

function isMetric(value: unknown): value is WorkforceMetric {
  return (
    hasExactKeys(value, ['key', 'label', 'value', 'tone']) &&
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label) &&
    Number.isSafeInteger(value.value) &&
    Number(value.value) >= 0 &&
    (value.tone === 'neutral' ||
      value.tone === 'info' ||
      value.tone === 'success' ||
      value.tone === 'warning')
  );
}

function isColumn(value: unknown): value is WorkforceColumn {
  return (
    hasExactKeys(value, ['key', 'label']) &&
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label)
  );
}

function isRow(value: unknown, screenId: string): value is PatientRegistryRow {
  return (
    hasExactKeys(
      value,
      ['id', 'status', 'revision', 'etag', 'values', 'allowedActionKeys'],
      ['patientId'],
    ) &&
    isUuid(value.id) &&
    isOptionalNullableUuid(value.patientId) &&
    isNonEmptyString(value.status) &&
    Number.isSafeInteger(value.revision) &&
    Number(value.revision) >= 0 &&
    value.etag === `"m3:${screenId}:${value.id as string}:${value.revision as number}"` &&
    isStringMap(value.values) &&
    isArrayOf(
      value.allowedActionKeys,
      (key): key is string => typeof key === 'string' && actionKeyPattern.test(key),
    ) &&
    hasUniqueValues(value.allowedActionKeys as string[])
  );
}

function isOption(value: unknown): value is WorkforceOption {
  return (
    hasExactKeys(value, ['value', 'label']) &&
    isNonEmptyString(value.value) &&
    isNonEmptyString(value.label)
  );
}

function isField(value: unknown): value is WorkforceField {
  return (
    hasExactKeys(value, ['key', 'label', 'inputType', 'required', 'options'], ['help']) &&
    typeof value.key === 'string' &&
    fieldKeyPattern.test(value.key) &&
    isNonEmptyString(value.label) &&
    typeof value.inputType === 'string' &&
    fieldTypes.has(value.inputType) &&
    typeof value.required === 'boolean' &&
    (value.help === undefined || value.help === null || typeof value.help === 'string') &&
    isArrayOf(value.options, isOption) &&
    hasUniqueValues((value.options as WorkforceOption[]).map((option) => option.value))
  );
}

function isAction(value: unknown): value is WorkforceAction {
  if (!(
    hasExactKeys(
      value,
      ['key', 'label', 'style', 'targetRequired', 'ifMatchRequired', 'reasonRequired', 'fields'],
      ['href'],
    ) &&
    typeof value.key === 'string' &&
    actionKeyPattern.test(value.key) &&
    isNonEmptyString(value.label) &&
    (value.style === 'primary' || value.style === 'link') &&
    typeof value.targetRequired === 'boolean' &&
    typeof value.ifMatchRequired === 'boolean' &&
    typeof value.reasonRequired === 'boolean' &&
    (value.href === undefined || value.href === null || typeof value.href === 'string') &&
    isArrayOf(value.fields, isField)
  )) {
    return false;
  }
  const fields = value.fields as WorkforceField[];
  if (!hasUniqueValues(fields.map((field) => field.key))) return false;
  if (value.style === 'link') {
    return (
      typeof value.href === 'string' &&
      patientLinkPattern.test(value.href) &&
      value.targetRequired === false &&
      value.ifMatchRequired === false &&
      value.reasonRequired === false &&
      fields.length === 0
    );
  }
  return (
    (value.href === undefined || value.href === null) &&
    (!value.ifMatchRequired || value.targetRequired)
  );
}

function isNotice(value: unknown): value is WorkforceNotice {
  return (
    hasExactKeys(value, ['tone', 'title', 'detail']) &&
    isNonEmptyString(value.tone) &&
    isNonEmptyString(value.title) &&
    isNonEmptyString(value.detail)
  );
}

function isImpactItem(value: unknown): value is WorkforceImpactItem {
  return (
    hasExactKeys(value, ['code', 'tone', 'detail', 'affectedCount']) &&
    typeof value.code === 'string' &&
    /^[a-z][a-z0-9_]{1,79}$/.test(value.code) &&
    (value.tone === 'impact' || value.tone === 'warning' || value.tone === 'blocker') &&
    isNonEmptyString(value.detail) &&
    Array.from(value.detail).length <= 240 &&
    Number.isSafeInteger(value.affectedCount) &&
    Number(value.affectedCount) >= 0
  );
}

export function patientRegistryScreenValidator(
  organizationId: string,
  screenId: string,
): PatientResponseValidator<PatientRegistryScreen> {
  return (value): value is PatientRegistryScreen => {
    if (!(
      hasExactKeys(value, [
        'organizationId',
        'screenId',
        'title',
        'purpose',
        'generatedAt',
        'metrics',
        'columns',
        'rows',
        'actions',
        'notices',
        'nextCursor',
        'pageSize',
      ]) &&
      value.organizationId === organizationId &&
      value.screenId === screenId &&
      screenPattern.test(screenId) &&
      isNonEmptyString(value.title) &&
      isNonEmptyString(value.purpose) &&
      isDateTime(value.generatedAt) &&
      isArrayOf(value.metrics, isMetric) &&
      isArrayOf(value.columns, isColumn) &&
      isArrayOf(value.rows, (row): row is PatientRegistryRow => isRow(row, screenId)) &&
      isArrayOf(value.actions, isAction) &&
      isArrayOf(value.notices, isNotice) &&
      Number.isSafeInteger(value.pageSize) &&
      Number(value.pageSize) >= 1 &&
      Number(value.pageSize) <= 100 &&
      (value.nextCursor === null ||
        (typeof value.nextCursor === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(value.nextCursor)))
    )) {
      return false;
    }
    const columns = value.columns as WorkforceColumn[];
    const rows = value.rows as PatientRegistryRow[];
    const actions = value.actions as WorkforceAction[];
    if (
      !hasUniqueValues(columns.map((column) => column.key)) ||
      !hasUniqueValues(rows.map((row) => row.id)) ||
      !hasUniqueValues(actions.map((action) => action.key))
    ) {
      return false;
    }
    const targetActions = new Set(
      actions.filter((action) => action.targetRequired).map((action) => action.key),
    );
    return rows.every((row) => row.allowedActionKeys.every((key) => targetActions.has(key)));
  };
}

export function patientRegistryImpactPreviewValidator(
  screenId: string,
  actionKey: string,
  targetId: string,
  revision: number,
): PatientResponseValidator<PatientRegistryImpactPreviewResponse> {
  return (value): value is PatientRegistryImpactPreviewResponse =>
    hasExactKeys(value, [
      'screenId',
      'actionKey',
      'targetId',
      'revision',
      'digest',
      'token',
      'expiresAt',
      'blocked',
      'items',
    ]) &&
    value.screenId === screenId &&
    screenPattern.test(value.screenId as string) &&
    value.actionKey === actionKey &&
    actionKeyPattern.test(value.actionKey as string) &&
    value.targetId === targetId &&
    isUuid(value.targetId) &&
    value.revision === revision &&
    Number.isSafeInteger(value.revision) &&
    Number(value.revision) >= 0 &&
    typeof value.digest === 'string' &&
    sha256Pattern.test(value.digest) &&
    typeof value.token === 'string' &&
    /^[A-Za-z0-9_-]{32,4096}$/.test(value.token) &&
    isDateTime(value.expiresAt) &&
    Date.parse(value.expiresAt) > Date.now() &&
    typeof value.blocked === 'boolean' &&
    isArrayOf(value.items, isImpactItem) &&
    value.items.length >= 1 &&
    value.items.length <= 32 &&
    value.blocked === value.items.some((item) => item.tone === 'blocker');
}
