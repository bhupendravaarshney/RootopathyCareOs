import type {
  AiRow,
  AiScreen,
  WorkforceAction,
  WorkforceColumn,
  WorkforceField,
  WorkforceMetric,
  WorkforceNotice,
  WorkforceOption,
} from './generated';

export type AiResponseValidator<T> = (value: unknown) => value is T;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const screenPattern = /^P8-(0[1-9]|10)$/;
const actionKeyPattern = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const fieldKeyPattern = /^[A-Za-z][A-Za-z0-9_.-]{0,79}$/;
const fieldTypes = new Set([
  'checkbox',
  'date',
  'datetime-local',
  'number',
  'select',
  'text',
  'textarea',
  'uuid',
]);

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

function isOptionalUuid(value: unknown): value is string | null | undefined {
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

function hasUniqueValues(values: readonly string[]): boolean {
  return new Set(values).size === values.length;
}

function isStringMap(value: unknown): value is Record<string, string> {
  return (
    isRecord(value) &&
    Object.entries(value).every(
      ([key, item]) => fieldKeyPattern.test(key) && typeof item === 'string',
    )
  );
}

function isMetric(value: unknown): value is WorkforceMetric {
  return (
    hasExactKeys(value, ['key', 'label', 'value', 'tone']) &&
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label) &&
    Number.isSafeInteger(value.value) &&
    Number(value.value) >= 0 &&
    isNonEmptyString(value.tone)
  );
}

function isColumn(value: unknown): value is WorkforceColumn {
  return (
    hasExactKeys(value, ['key', 'label']) &&
    typeof value.key === 'string' &&
    fieldKeyPattern.test(value.key) &&
    isNonEmptyString(value.label)
  );
}

function isRow(
  value: unknown,
  screenId: string,
  etagNamespace: string,
  optionalUuidKeys: readonly string[],
): value is AiRow {
  return (
    hasExactKeys(
      value,
      ['id', 'status', 'revision', 'etag', 'values', 'allowedActionKeys'],
      optionalUuidKeys,
    ) &&
    isUuid(value.id) &&
    optionalUuidKeys.every((key) => isOptionalUuid(value[key])) &&
    isNonEmptyString(value.status) &&
    Number.isSafeInteger(value.revision) &&
    Number(value.revision) >= 0 &&
    value.etag ===
      `"${etagNamespace}:${screenId}:${value.id as string}:${value.revision as number}"` &&
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
    value.style === 'primary' &&
    typeof value.targetRequired === 'boolean' &&
    typeof value.ifMatchRequired === 'boolean' &&
    typeof value.reasonRequired === 'boolean' &&
    (value.href === undefined || value.href === null) &&
    isArrayOf(value.fields, isField)
  )) {
    return false;
  }
  const fields = value.fields as WorkforceField[];
  return (
    hasUniqueValues(fields.map((field) => field.key)) &&
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

export function governedScreenValidator<T>(
  organizationId: string,
  screenId: string,
  screenPattern: RegExp,
  etagNamespace: string,
  optionalUuidKeys: readonly string[],
): AiResponseValidator<T> {
  return (value): value is T => {
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
      isArrayOf(value.rows, (row): row is AiRow =>
        isRow(row, screenId, etagNamespace, optionalUuidKeys),
      ) &&
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
    const rows = value.rows as AiRow[];
    const actions = value.actions as WorkforceAction[];
    if (
      !hasUniqueValues(columns.map((column) => column.key)) ||
      !hasUniqueValues(rows.map((row) => row.id)) ||
      !hasUniqueValues(actions.map((action) => action.key))
    ) {
      return false;
    }
    const targets = new Set(
      actions.filter((action) => action.targetRequired).map((action) => action.key),
    );
    return rows.every((row) => row.allowedActionKeys.every((key) => targets.has(key)));
  };
}

export function aiScreenValidator(
  organizationId: string,
  screenId: string,
): AiResponseValidator<AiScreen> {
  return governedScreenValidator<AiScreen>(organizationId, screenId, screenPattern, 'm8', [
    'patientId',
    'encounterId',
    'inputManifestId',
    'outputId',
    'outputVersionId',
    'safetyFlagId',
  ]);
}
