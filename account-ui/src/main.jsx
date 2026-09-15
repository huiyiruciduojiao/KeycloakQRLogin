import React, { Suspense, useMemo } from 'react';
import { createRoot } from 'react-dom/client';
import { KeycloakProvider, Header, PageNav, routes } from '@keycloak/keycloak-account-ui';
import { Page, Spinner } from '@patternfly/react-core';
import { createBrowserRouter, RouterProvider, Outlet, Navigate } from 'react-router-dom';
import { createInstance } from 'i18next';
import FetchBackend from 'i18next-fetch-backend';
import { initReactI18next } from 'react-i18next';
import '@patternfly/react-core/dist/styles/base.css';
import '@patternfly/patternfly/patternfly-addons.css';
import '../node_modules/@keycloak/keycloak-account-ui/lib/keycloak-account-ui.css';
import '../../src/main/resources/theme/qrlogin/account/resources/css/ysit-account.css';
import '../../src/main/resources/theme/qrlogin/account/resources/css/avatar.css';
import AvatarPage from '../../src/main/resources/theme/qrlogin/account/resources/js/avatar-page.js';

const environment = JSON.parse(document.getElementById('environment').textContent);
const i18n = createInstance({
  lng: environment.locale, fallbackLng: 'en', nsSeparator: false, interpolation: { escapeValue: false },
  backend: {
    loadPath: `${environment.serverBaseUrl}/resources/${encodeURIComponent(environment.realm)}/account/{{lng}}`,
    parse: data => Object.fromEntries(JSON.parse(data).map(({ key, value }) => [key, value]))
  }
});
await i18n.use(FetchBackend).use(initReactI18next).init();
const content = await fetch(`${environment.resourceUrl}/content.json`).then(response => {
  if (!response.ok) throw Error('Account navigation unavailable');
  return response.json();
});

function availableRoutes(items) {
  return items.flatMap(item => {
    if (item.isVisible && !environment.features[item.isVisible]) return [];
    if (item.children) return availableRoutes(item.children);
    if (item.path === 'content/avatar') return [{ path: item.path, element: <AvatarPage /> }];
    const route = routes.find(route => route.path === item.path);
    return route ? [route] : [];
  });
}
function AccountRoot() {
  const router = useMemo(() => createBrowserRouter([{
    path: decodeURIComponent(new URL(environment.baseUrl).pathname),
    element: <Page header={<Header />} sidebar={<PageNav />} isManagedSidebar>
      <Suspense fallback={<Spinner />}><Outlet /></Suspense>
    </Page>,
    children: [...availableRoutes(content), { path: '*', element: <Navigate to=".." replace /> }]
  }]), []);
  return <RouterProvider router={router} />;
}
createRoot(document.getElementById('app')).render(<KeycloakProvider environment={environment}><AccountRoot /></KeycloakProvider>);
