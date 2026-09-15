// Keycloak 26.7.3 bundles PatternFly's PageContext into its component library.
// The host Page must share that context with the bundled Header and PageNav.
// Keep this adapter tied to the pinned upstream release; fail builds on drift.
export function accountPageContext() {
  let patched = 0, avatarPatched = 0;
  return {
    name: 'keycloak-26.7.3-shared-page-context',
    enforce: 'pre',
    transform(code, id) {
      if (!id.replaceAll('\\', '/').includes('/@keycloak/keycloak-account-ui/lib/')) return;
      const picture = /const (\w+) = (\w+)\.idTokenParsed\?\.picture;/g;
      if ([...code.matchAll(picture)].length === 1) {
        avatarPatched++;
        code = `import { useConsoleAvatar } from '${new URL('../../src/main/resources/theme/qrlogin/common-avatar/use-console-avatar.js', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')}';\n` +
          code.replace(picture, 'const $1 = useConsoleAvatar($2) ?? $2.idTokenParsed?.picture;');
      }
      const defaults = code.match(/(\w+) = \{\s*isManagedSidebar: !1,\s*isSidebarOpen: !1,\s*onSidebarToggle: \(\) => null,\s*width: null,\s*height: null,\s*getBreakpoint: \w+,\s*getVerticalBreakpoint: \w+\s*\}/);
      if (!defaults) return;
      const creation = new RegExp(`\\b\\w+\\.createContext\\(${defaults[1]}\\)`, 'g');
      if ([...code.matchAll(creation)].length !== 1) throw Error('Unexpected Keycloak PageContext structure');
      patched++;
      return {
        code: `import { PageContext as avatarSharedPageContext } from '@patternfly/react-core/dist/esm/components/Page/PageContext.js';\n` + code.replace(creation, 'avatarSharedPageContext'),
        map: null
      };
    },
    buildEnd(error) {
      if (!error && (patched !== 1 || avatarPatched !== 1)) throw Error('Keycloak masthead/PageContext adapters did not match exactly once; review the pinned library');
    }
  };
}
