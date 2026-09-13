import { useEffect, useState } from 'react';
import { PrototypeScreenPage } from './pages/PrototypeScreenPage';

const routeId = () => window.location.hash.replace(/^#\/?/, '').toUpperCase() || 'M1-05';

export default function App() {
  const [id, setId] = useState(routeId);
  useEffect(() => {
    const onHashChange = () => setId(routeId());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);
  return <PrototypeScreenPage id={id} />;
}
