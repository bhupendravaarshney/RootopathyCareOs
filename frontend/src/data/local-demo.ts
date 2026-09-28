export const LOCAL_DEMO_ORGANIZATION_ID = '01900000-0000-7000-8000-000000000001';

export function isLocalDemoOrganization(organizationId: string): boolean {
  return organizationId === LOCAL_DEMO_ORGANIZATION_ID;
}
