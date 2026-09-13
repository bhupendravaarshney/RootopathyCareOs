import { render, screen } from '@testing-library/react';
import App from './App';
import { screens } from './data/screens';

describe('CareOS clickable prototype', () => {
  it('registers all M1, M2 and COS screens', () => {
    expect(screens).toHaveLength(79);
    expect(new Set(screens.map((item) => item.id)).size).toBe(79);
  });

  it('renders the administration dashboard by default', () => {
    window.location.hash = '#/M1-05';
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Administration dashboard' })).toBeInTheDocument();
    expect(screen.getAllByText('M1-05')).toHaveLength(2);
  });
});
