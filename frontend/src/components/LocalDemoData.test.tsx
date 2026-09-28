import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { isLocalDemoOrganization, LOCAL_DEMO_ORGANIZATION_ID } from '../data/local-demo';
import { LocalDemoBanner, LocalDemoData } from './LocalDemoData';

describe('local demo experience', () => {
  it('shows relevant, clearly disclosed billing examples', () => {
    render(<LocalDemoData screenId="P11-01" />);

    expect(screen.getByText('Local demo data')).toBeVisible();
    expect(screen.getByText('Example records for Billing dashboard')).toBeVisible();
    expect(screen.getByText('Consultation invoice')).toBeVisible();
    expect(screen.getByText(/not stored in the database/i)).toBeVisible();
  });

  it('identifies only the synthetic local organization and explains administrator access', () => {
    expect(isLocalDemoOrganization(LOCAL_DEMO_ORGANIZATION_ID)).toBe(true);
    expect(isLocalDemoOrganization('22222222-2222-4222-8222-222222222222')).toBe(false);

    render(<LocalDemoBanner />);
    expect(screen.getByText('Local demo administrator')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Administrator overview' })).toHaveAttribute(
      'href',
      '#/M1-05',
    );
  });
});
