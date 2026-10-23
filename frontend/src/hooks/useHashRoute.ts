import { useEffect, useState } from 'react';

export type Route =
  | { page: 'overview' }
  | { page: 'incidents'; status?: 'ACTIVE' | 'RESOLVED' }
  | { page: 'incident'; id: number }
  | { page: 'activity' };

/** Parses `#/incidents/12`-style hashes. Unknown hashes fall back to the overview. */
export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '');
  const [pathname, search = ''] = path.split('?');
  const parts = pathname.split('/').filter(Boolean);
  if (parts[0] === 'incidents' && parts[1] && /^\d+$/.test(parts[1])) {
    return { page: 'incident', id: Number(parts[1]) };
  }
  if (parts[0] === 'incidents') {
    const status = new URLSearchParams(search).get('status')?.toUpperCase();
    return status === 'ACTIVE' || status === 'RESOLVED'
      ? { page: 'incidents', status }
      : { page: 'incidents' };
  }
  if (parts[0] === 'activity') return { page: 'activity' };
  return { page: 'overview' };
}

export function hrefFor(route: Route): string {
  switch (route.page) {
    case 'overview':
      return '#/';
    case 'incidents':
      return route.status ? `#/incidents?status=${route.status.toLowerCase()}` : '#/incidents';
    case 'incident':
      return `#/incidents/${route.id}`;
    case 'activity':
      return '#/activity';
  }
}

/** Hash-based routing: deep links to an incident work without any server-side routes. */
export function useHashRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => {
      setRoute(parseRoute(window.location.hash));
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
