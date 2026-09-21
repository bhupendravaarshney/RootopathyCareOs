import type {
  AuditEvidenceDetail,
  AuditEvidenceItem,
  AuditEvidencePage,
  ConfigurationActivation,
  ConfigurationActivationDirectory,
  ConfigurationHistoryChange,
  ConfigurationHistoryItem,
  ConfigurationHistoryPage,
  ConfigurationPendingChange,
  EvidenceExportAccessResponse,
  EvidenceExportDirectory,
  EvidenceExportJob,
  IdentifierScheme,
  IdentifierSchemeDirectory,
  IdentifierSchemeVersion,
  OperatingHoursBatch,
  OperatingHoursDirectory,
  OperatingHoursException,
  OperatingHoursExceptionInterval,
  OperatingHoursInterval,
  OperatingHoursOverview,
  OperatingHoursOverviewBatch,
  ServiceAssignment,
  ServiceAssignmentDirectory,
  ServiceCatalogue,
  ServiceDefinition,
} from './generated';

export type ResponseValidator<T> = (value: unknown) => value is T;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const datePattern = /^\d{4}-\d{2}-\d{2}$/;
const digestPattern = /^[0-9a-f]{64}$/;
const exportFilenamePattern = /^careos-(history|audit)-[0-9a-f-]{36}\.(csv|jsonl)$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function exactRecord(value: unknown, fields: readonly string[]): value is Record<string, unknown> {
  if (!isRecord(value)) return false;
  const keys = Object.keys(value);
  return keys.length === fields.length && fields.every((field) => keys.includes(field));
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isNullableUuid(value: unknown): value is string | null {
  return value === null || isUuid(value);
}

function isDateTime(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function isNullableDateTime(value: unknown): value is string | null {
  return value === null || isDateTime(value);
}

function isDate(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    datePattern.test(value) &&
    Number.isFinite(Date.parse(`${value}T00:00:00Z`))
  );
}

function isInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value);
}

function isNonNegativeInteger(value: unknown): value is number {
  return isInteger(value) && value >= 0;
}

function isNullableNonNegativeInteger(value: unknown): value is number | null {
  return value === null || isNonNegativeInteger(value);
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(isString);
}

function isArrayOf<T>(value: unknown, validator: ResponseValidator<T>): value is T[] {
  return Array.isArray(value) && value.every(validator);
}

function isOneOf<const T extends string>(value: unknown, values: readonly T[]): value is T {
  return typeof value === 'string' && values.includes(value as T);
}

function isOperatingHoursOverviewBatch(value: unknown): value is OperatingHoursOverviewBatch {
  return (
    exactRecord(value, [
      'batchId',
      'targetType',
      'targetId',
      'timezone',
      'effectiveFrom',
      'effectiveTo',
      'status',
      'lockVersion',
    ]) &&
    isUuid(value.batchId) &&
    isOneOf(value.targetType, ['facility', 'location']) &&
    isUuid(value.targetId) &&
    isNonEmptyString(value.timezone) &&
    isDateTime(value.effectiveFrom) &&
    isNullableDateTime(value.effectiveTo) &&
    isNonEmptyString(value.status) &&
    isNonNegativeInteger(value.lockVersion)
  );
}

export function operatingHoursOverviewValidator(
  organizationId: string,
): ResponseValidator<OperatingHoursOverview> {
  return (value): value is OperatingHoursOverview =>
    exactRecord(value, ['organizationId', 'canManage', 'batches', 'evaluatedAt']) &&
    value.organizationId === organizationId &&
    typeof value.canManage === 'boolean' &&
    isArrayOf(value.batches, isOperatingHoursOverviewBatch) &&
    isDateTime(value.evaluatedAt);
}

function isOperatingHoursInterval(value: unknown): value is OperatingHoursInterval {
  return (
    exactRecord(value, ['weekday', 'startMinute', 'endMinute', 'endsNextDay']) &&
    isInteger(value.weekday) &&
    value.weekday >= 1 &&
    value.weekday <= 7 &&
    isInteger(value.startMinute) &&
    value.startMinute >= 0 &&
    value.startMinute <= 1439 &&
    isInteger(value.endMinute) &&
    value.endMinute >= 0 &&
    value.endMinute <= 1439 &&
    typeof value.endsNextDay === 'boolean'
  );
}

function isOperatingHoursExceptionInterval(
  value: unknown,
): value is OperatingHoursExceptionInterval {
  return (
    exactRecord(value, ['startMinute', 'endMinute', 'endsNextDay']) &&
    isInteger(value.startMinute) &&
    value.startMinute >= 0 &&
    value.startMinute <= 1439 &&
    isInteger(value.endMinute) &&
    value.endMinute >= 0 &&
    value.endMinute <= 1439 &&
    typeof value.endsNextDay === 'boolean'
  );
}

function isOperatingHoursException(value: unknown): value is OperatingHoursException {
  return (
    exactRecord(value, ['localDate', 'closed', 'label', 'reasonCode', 'intervals']) &&
    isDate(value.localDate) &&
    typeof value.closed === 'boolean' &&
    isNullableString(value.label) &&
    isNullableString(value.reasonCode) &&
    isArrayOf(value.intervals, isOperatingHoursExceptionInterval) &&
    (!value.closed || value.intervals.length === 0)
  );
}

function isOperatingHoursBatch(value: unknown): value is OperatingHoursBatch {
  return (
    exactRecord(value, [
      'batchId',
      'timezone',
      'effectiveFrom',
      'effectiveTo',
      'status',
      'lockVersion',
      'intervals',
      'exceptions',
    ]) &&
    isUuid(value.batchId) &&
    isNonEmptyString(value.timezone) &&
    isDateTime(value.effectiveFrom) &&
    isNullableDateTime(value.effectiveTo) &&
    isNonEmptyString(value.status) &&
    isNonNegativeInteger(value.lockVersion) &&
    isArrayOf(value.intervals, isOperatingHoursInterval) &&
    isArrayOf(value.exceptions, isOperatingHoursException)
  );
}

export function operatingHoursDirectoryValidator(
  organizationId: string,
  targetType?: 'facility' | 'location',
  targetId?: string,
): ResponseValidator<OperatingHoursDirectory> {
  return (value): value is OperatingHoursDirectory =>
    exactRecord(value, [
      'organizationId',
      'targetType',
      'targetId',
      'canManage',
      'batches',
      'evaluatedAt',
    ]) &&
    value.organizationId === organizationId &&
    isOneOf(value.targetType, ['facility', 'location']) &&
    (targetType === undefined || value.targetType === targetType) &&
    isUuid(value.targetId) &&
    (targetId === undefined || value.targetId === targetId) &&
    typeof value.canManage === 'boolean' &&
    isArrayOf(value.batches, isOperatingHoursBatch) &&
    isDateTime(value.evaluatedAt);
}

function isServiceDefinition(value: unknown): value is ServiceDefinition {
  return (
    exactRecord(value, [
      'serviceId',
      'serviceCode',
      'displayName',
      'clinicalName',
      'description',
      'codingSystem',
      'codingCode',
      'ownerResponsibilityId',
      'status',
      'retirementReason',
      'lockVersion',
      'createdAt',
      'updatedAt',
    ]) &&
    isUuid(value.serviceId) &&
    isNonEmptyString(value.serviceCode) &&
    isNonEmptyString(value.displayName) &&
    isNullableString(value.clinicalName) &&
    isNullableString(value.description) &&
    isNullableString(value.codingSystem) &&
    isNullableString(value.codingCode) &&
    isNullableUuid(value.ownerResponsibilityId) &&
    isNonEmptyString(value.status) &&
    isNullableString(value.retirementReason) &&
    isNonNegativeInteger(value.lockVersion) &&
    isDateTime(value.createdAt) &&
    isDateTime(value.updatedAt)
  );
}

export function serviceCatalogueValidator(
  organizationId: string,
): ResponseValidator<ServiceCatalogue> {
  return (value): value is ServiceCatalogue =>
    exactRecord(value, [
      'organizationId',
      'canManage',
      'canManageLifecycle',
      'services',
      'evaluatedAt',
    ]) &&
    value.organizationId === organizationId &&
    typeof value.canManage === 'boolean' &&
    typeof value.canManageLifecycle === 'boolean' &&
    isArrayOf(value.services, isServiceDefinition) &&
    isDateTime(value.evaluatedAt);
}

function isServiceAssignment(value: unknown): value is ServiceAssignment {
  return (
    exactRecord(value, [
      'assignmentId',
      'serviceId',
      'facilityId',
      'locationId',
      'capacity',
      'availabilityNotes',
      'prerequisites',
      'effectiveFrom',
      'effectiveTo',
      'status',
      'lockVersion',
      'createdAt',
      'updatedAt',
    ]) &&
    isUuid(value.assignmentId) &&
    isUuid(value.serviceId) &&
    isUuid(value.facilityId) &&
    isNullableUuid(value.locationId) &&
    isNullableNonNegativeInteger(value.capacity) &&
    isNullableString(value.availabilityNotes) &&
    isStringArray(value.prerequisites) &&
    isDateTime(value.effectiveFrom) &&
    isNullableDateTime(value.effectiveTo) &&
    isNonEmptyString(value.status) &&
    isNonNegativeInteger(value.lockVersion) &&
    isDateTime(value.createdAt) &&
    isDateTime(value.updatedAt)
  );
}

export function serviceAssignmentDirectoryValidator(
  organizationId: string,
): ResponseValidator<ServiceAssignmentDirectory> {
  return (value): value is ServiceAssignmentDirectory =>
    exactRecord(value, [
      'organizationId',
      'canManage',
      'canManageLifecycle',
      'assignments',
      'evaluatedAt',
    ]) &&
    value.organizationId === organizationId &&
    typeof value.canManage === 'boolean' &&
    typeof value.canManageLifecycle === 'boolean' &&
    isArrayOf(value.assignments, isServiceAssignment) &&
    isDateTime(value.evaluatedAt);
}

function isIdentifierSchemeVersion(value: unknown): value is IdentifierSchemeVersion {
  return (
    exactRecord(value, [
      'versionId',
      'versionNumber',
      'prefix',
      'pattern',
      'alphabet',
      'checkDigitAlgorithm',
      'sequenceStart',
      'sequenceIncrement',
      'padding',
      'previewSamples',
      'effectiveFrom',
      'status',
      'lockVersion',
    ]) &&
    isUuid(value.versionId) &&
    isInteger(value.versionNumber) &&
    value.versionNumber >= 1 &&
    isString(value.prefix) &&
    isNonEmptyString(value.pattern) &&
    isNonEmptyString(value.alphabet) &&
    isNullableString(value.checkDigitAlgorithm) &&
    isNonNegativeInteger(value.sequenceStart) &&
    isInteger(value.sequenceIncrement) &&
    value.sequenceIncrement >= 1 &&
    isInteger(value.padding) &&
    value.padding >= 1 &&
    isStringArray(value.previewSamples) &&
    isDateTime(value.effectiveFrom) &&
    isNonEmptyString(value.status) &&
    isNonNegativeInteger(value.lockVersion)
  );
}

function isIdentifierScheme(value: unknown): value is IdentifierScheme {
  return (
    exactRecord(value, [
      'schemeId',
      'schemeKey',
      'scopeType',
      'scopeId',
      'description',
      'status',
      'lockVersion',
      'versions',
    ]) &&
    isUuid(value.schemeId) &&
    isNonEmptyString(value.schemeKey) &&
    isNonEmptyString(value.scopeType) &&
    isNullableUuid(value.scopeId) &&
    isNullableString(value.description) &&
    isNonEmptyString(value.status) &&
    isNonNegativeInteger(value.lockVersion) &&
    isArrayOf(value.versions, isIdentifierSchemeVersion)
  );
}

export function identifierSchemeDirectoryValidator(
  organizationId: string,
): ResponseValidator<IdentifierSchemeDirectory> {
  return (value): value is IdentifierSchemeDirectory =>
    exactRecord(value, [
      'organizationId',
      'canManage',
      'canActivate',
      'canRetire',
      'schemes',
      'evaluatedAt',
    ]) &&
    value.organizationId === organizationId &&
    typeof value.canManage === 'boolean' &&
    typeof value.canActivate === 'boolean' &&
    typeof value.canRetire === 'boolean' &&
    isArrayOf(value.schemes, isIdentifierScheme) &&
    isDateTime(value.evaluatedAt);
}

function isConfigurationPendingChange(value: unknown): value is ConfigurationPendingChange {
  return (
    exactRecord(value, [
      'subjectType',
      'subjectId',
      'expectedRevision',
      'changeType',
      'label',
      'currentStatus',
    ]) &&
    isNonEmptyString(value.subjectType) &&
    isUuid(value.subjectId) &&
    isNonNegativeInteger(value.expectedRevision) &&
    isNonEmptyString(value.changeType) &&
    isNonEmptyString(value.label) &&
    isNonEmptyString(value.currentStatus)
  );
}

function isConfigurationActivation(value: unknown): value is ConfigurationActivation {
  return (
    exactRecord(value, [
      'configurationId',
      'displayNumber',
      'parentVersionId',
      'baselineDigest',
      'changeSummary',
      'requestedEffectiveAt',
      'status',
      'lockVersion',
      'makerId',
      'canSubmit',
      'canApprove',
      'canActivate',
      'validationResultId',
      'resultDigest',
      'blockerCount',
      'warningCount',
      'validationExpiresAt',
      'approvalId',
      'decision',
      'checkerId',
      'approvalExpiresAt',
      'activatedAt',
    ]) &&
    isUuid(value.configurationId) &&
    isNonEmptyString(value.displayNumber) &&
    isNullableUuid(value.parentVersionId) &&
    isNonEmptyString(value.baselineDigest) &&
    isNonEmptyString(value.changeSummary) &&
    isDateTime(value.requestedEffectiveAt) &&
    isNonEmptyString(value.status) &&
    isNonNegativeInteger(value.lockVersion) &&
    isUuid(value.makerId) &&
    typeof value.canSubmit === 'boolean' &&
    typeof value.canApprove === 'boolean' &&
    typeof value.canActivate === 'boolean' &&
    isNullableUuid(value.validationResultId) &&
    isNullableString(value.resultDigest) &&
    isNonNegativeInteger(value.blockerCount) &&
    isNonNegativeInteger(value.warningCount) &&
    isNullableDateTime(value.validationExpiresAt) &&
    isNullableUuid(value.approvalId) &&
    isNullableString(value.decision) &&
    isNullableUuid(value.checkerId) &&
    isNullableDateTime(value.approvalExpiresAt) &&
    isNullableDateTime(value.activatedAt)
  );
}

export function configurationActivationDirectoryValidator(
  organizationId: string,
): ResponseValidator<ConfigurationActivationDirectory> {
  return (value): value is ConfigurationActivationDirectory =>
    exactRecord(value, [
      'organizationId',
      'canValidate',
      'canSubmit',
      'canApprove',
      'canActivate',
      'pendingChanges',
      'configurations',
      'evaluatedAt',
    ]) &&
    value.organizationId === organizationId &&
    typeof value.canValidate === 'boolean' &&
    typeof value.canSubmit === 'boolean' &&
    typeof value.canApprove === 'boolean' &&
    typeof value.canActivate === 'boolean' &&
    isArrayOf(value.pendingChanges, isConfigurationPendingChange) &&
    isArrayOf(value.configurations, isConfigurationActivation) &&
    isDateTime(value.evaluatedAt);
}

function isConfigurationHistoryChange(value: unknown): value is ConfigurationHistoryChange {
  return (
    exactRecord(value, [
      'subjectType',
      'subjectId',
      'baselineRevision',
      'newRevision',
      'changeType',
    ]) &&
    isNonEmptyString(value.subjectType) &&
    isUuid(value.subjectId) &&
    isNonNegativeInteger(value.baselineRevision) &&
    isInteger(value.newRevision) &&
    value.newRevision >= 1 &&
    isNonEmptyString(value.changeType)
  );
}

function isConfigurationHistoryItem(value: unknown): value is ConfigurationHistoryItem {
  return (
    exactRecord(value, [
      'configurationId',
      'displayNumber',
      'parentVersionId',
      'status',
      'changeSummary',
      'reasonProjection',
      'makerId',
      'checkerId',
      'activatorId',
      'requestedEffectiveAt',
      'activatedAt',
      'supersededAt',
      'resultDigest',
      'gateCatalogueVersion',
      'approvalPolicyVersion',
      'correlationId',
      'lockVersion',
      'recordedAt',
      'changes',
    ]) &&
    isUuid(value.configurationId) &&
    isNonEmptyString(value.displayNumber) &&
    isNullableUuid(value.parentVersionId) &&
    isNonEmptyString(value.status) &&
    isNonEmptyString(value.changeSummary) &&
    isString(value.reasonProjection) &&
    isUuid(value.makerId) &&
    isNullableUuid(value.checkerId) &&
    isNullableUuid(value.activatorId) &&
    isDateTime(value.requestedEffectiveAt) &&
    isNullableDateTime(value.activatedAt) &&
    isNullableDateTime(value.supersededAt) &&
    isNullableString(value.resultDigest) &&
    isNullableString(value.gateCatalogueVersion) &&
    isNullableString(value.approvalPolicyVersion) &&
    isNonEmptyString(value.correlationId) &&
    isNonNegativeInteger(value.lockVersion) &&
    isDateTime(value.recordedAt) &&
    isArrayOf(value.changes, isConfigurationHistoryChange)
  );
}

export function configurationHistoryPageValidator(
  organizationId: string,
): ResponseValidator<ConfigurationHistoryPage> {
  return (value): value is ConfigurationHistoryPage =>
    exactRecord(value, ['organizationId', 'asOf', 'items', 'pageSize', 'hasMore', 'nextCursor']) &&
    value.organizationId === organizationId &&
    isDateTime(value.asOf) &&
    isArrayOf(value.items, isConfigurationHistoryItem) &&
    isNonNegativeInteger(value.pageSize) &&
    typeof value.hasMore === 'boolean' &&
    isNullableString(value.nextCursor);
}

function isAuditEvidenceItem(value: unknown): value is AuditEvidenceItem {
  return (
    exactRecord(value, [
      'eventId',
      'occurredAt',
      'actorId',
      'operationKey',
      'eventName',
      'schemaVersion',
      'subjectType',
      'subjectId',
      'outcome',
      'risk',
      'correlationId',
      'redacted',
    ]) &&
    isUuid(value.eventId) &&
    isDateTime(value.occurredAt) &&
    isUuid(value.actorId) &&
    isNonEmptyString(value.operationKey) &&
    isNonEmptyString(value.eventName) &&
    isInteger(value.schemaVersion) &&
    value.schemaVersion >= 1 &&
    isNonEmptyString(value.subjectType) &&
    isUuid(value.subjectId) &&
    isOneOf(value.outcome, ['success', 'failure']) &&
    isOneOf(value.risk, ['standard', 'high', 'restricted']) &&
    isNonEmptyString(value.correlationId) &&
    typeof value.redacted === 'boolean'
  );
}

export function auditEvidencePageValidator(
  organizationId: string,
): ResponseValidator<AuditEvidencePage> {
  return (value): value is AuditEvidencePage =>
    exactRecord(value, ['organizationId', 'asOf', 'items', 'pageSize', 'hasMore', 'nextCursor']) &&
    value.organizationId === organizationId &&
    isDateTime(value.asOf) &&
    isArrayOf(value.items, isAuditEvidenceItem) &&
    isNonNegativeInteger(value.pageSize) &&
    typeof value.hasMore === 'boolean' &&
    isNullableString(value.nextCursor);
}

export function auditEvidenceDetailValidator(
  organizationId: string,
  eventId: string,
): ResponseValidator<AuditEvidenceDetail> {
  return (value): value is AuditEvidenceDetail =>
    exactRecord(value, [
      'eventId',
      'occurredAt',
      'actorId',
      'operationKey',
      'eventName',
      'schemaVersion',
      'subjectType',
      'subjectId',
      'outcome',
      'risk',
      'correlationId',
      'redacted',
      'organizationId',
      'recordedAt',
      'purposeCode',
      'reasonProjection',
      'payload',
      'registryVersion',
    ]) &&
    value.organizationId === organizationId &&
    value.eventId === eventId &&
    isDateTime(value.occurredAt) &&
    isUuid(value.actorId) &&
    isNonEmptyString(value.operationKey) &&
    isNonEmptyString(value.eventName) &&
    isInteger(value.schemaVersion) &&
    value.schemaVersion >= 1 &&
    isNonEmptyString(value.subjectType) &&
    isUuid(value.subjectId) &&
    isOneOf(value.outcome, ['success', 'failure']) &&
    isOneOf(value.risk, ['standard', 'high', 'restricted']) &&
    isNonEmptyString(value.correlationId) &&
    typeof value.redacted === 'boolean' &&
    isDateTime(value.recordedAt) &&
    isNonEmptyString(value.purposeCode) &&
    isNullableString(value.reasonProjection) &&
    isRecord(value.payload) &&
    isNonEmptyString(value.registryVersion);
}

const exportProjections = [
  'history-summary-v1',
  'history-detail-v1',
  'audit-summary-v1',
  'audit-detail-v1',
] as const;
const exportStatuses = [
  'requested',
  'authorized',
  'denied',
  'running',
  'ready',
  'expired',
  'disposed',
  'failed',
] as const;

function isEvidenceExportJob(value: unknown): value is EvidenceExportJob {
  return (
    exactRecord(value, [
      'exportId',
      'requesterId',
      'projection',
      'format',
      'purposeCode',
      'status',
      'canApprove',
      'canAccess',
      'approvalId',
      'approverId',
      'rowCount',
      'byteCount',
      'artifactDigest',
      'readyAt',
      'expiresAt',
      'failureCode',
      'lockVersion',
      'createdAt',
      'updatedAt',
    ]) &&
    isUuid(value.exportId) &&
    isUuid(value.requesterId) &&
    isOneOf(value.projection, exportProjections) &&
    isOneOf(value.format, ['csv', 'jsonl']) &&
    isNonEmptyString(value.purposeCode) &&
    isOneOf(value.status, exportStatuses) &&
    typeof value.canApprove === 'boolean' &&
    typeof value.canAccess === 'boolean' &&
    isNullableUuid(value.approvalId) &&
    isNullableUuid(value.approverId) &&
    isNullableNonNegativeInteger(value.rowCount) &&
    isNullableNonNegativeInteger(value.byteCount) &&
    (value.artifactDigest === null ||
      (typeof value.artifactDigest === 'string' && digestPattern.test(value.artifactDigest))) &&
    isNullableDateTime(value.readyAt) &&
    isNullableDateTime(value.expiresAt) &&
    isNullableString(value.failureCode) &&
    isNonNegativeInteger(value.lockVersion) &&
    isDateTime(value.createdAt) &&
    isDateTime(value.updatedAt)
  );
}

export function evidenceExportDirectoryValidator(
  organizationId: string,
): ResponseValidator<EvidenceExportDirectory> {
  return (value): value is EvidenceExportDirectory =>
    exactRecord(value, [
      'organizationId',
      'canRequest',
      'canApprove',
      'canAccess',
      'jobs',
      'evaluatedAt',
    ]) &&
    value.organizationId === organizationId &&
    typeof value.canRequest === 'boolean' &&
    typeof value.canApprove === 'boolean' &&
    typeof value.canAccess === 'boolean' &&
    isArrayOf(value.jobs, isEvidenceExportJob) &&
    isDateTime(value.evaluatedAt);
}

function isHttpUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  try {
    const url = new URL(value);
    return (
      (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password
    );
  } catch {
    return false;
  }
}

export function evidenceExportAccessValidator(
  exportId: string,
): ResponseValidator<EvidenceExportAccessResponse> {
  return (value): value is EvidenceExportAccessResponse =>
    exactRecord(value, [
      'exportId',
      'downloadUrl',
      'expiresAt',
      'artifactDigest',
      'contentType',
      'filename',
    ]) &&
    value.exportId === exportId &&
    isHttpUrl(value.downloadUrl) &&
    isDateTime(value.expiresAt) &&
    typeof value.artifactDigest === 'string' &&
    digestPattern.test(value.artifactDigest) &&
    isOneOf(value.contentType, ['text/csv', 'application/x-ndjson']) &&
    typeof value.filename === 'string' &&
    exportFilenamePattern.test(value.filename);
}
