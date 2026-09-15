import { defineConfig } from 'vite';
import { accountPageContext } from './scripts/account-page-context.mjs';

export default defineConfig({
  base: './',
  plugins: [accountPageContext()],
  build: { target: 'es2022', manifest: true, outDir: 'dist', rollupOptions: { input: 'src/main.jsx' } },
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'react-i18next', 'i18next', '@keycloak/keycloak-ui-shared', '@keycloak/keycloak-account-ui'] }
});
