"""Add a masthead hook to the pinned official Admin UI without replacing its pages.

Usage: python scripts/build-admin-avatar.py /path/org.keycloak.keycloak-admin-ui-26.7.3.jar
Generated resources retain upstream Apache-2.0 code. Upgrade requires adapter review.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
source = Path(sys.argv[1]).resolve()
if source.name != 'org.keycloak.keycloak-admin-ui-26.7.3.jar':
    raise ValueError('Only the pinned Keycloak 26.7.3 distribution is supported')
resources = root / 'src/main/resources/theme/qrlogin/admin/resources'
hook = (root / 'src/main/resources/theme/qrlogin/common-avatar/use-console-avatar.js').read_bytes()
digest = hashlib.sha256(source.read_bytes() + hook).hexdigest()[:16]
generated = (resources / 'avatar-admin').resolve()
if generated.parent != resources.resolve() or generated.name != 'avatar-admin':
    raise ValueError('Unsafe generated directory')
prefix = 'theme/keycloak.v2/admin/resources/'
with zipfile.ZipFile(source) as archive:
    manifest = json.loads(archive.read(prefix + '.vite/manifest.json'))
    entries = [c for c in manifest.values() if c.get('isEntry')]
    if len(entries) != 1:
        raise ValueError('Unexpected native Admin UI entry points')
    entry = entries[0]['file']
    main = archive.read(prefix + entry).decode('utf-8')
    matcher = r'const (\w+)=(\w+)\.idTokenParsed\?\.picture;'
    if len(re.findall(matcher, main)) != 1:
        raise ValueError('Native masthead changed; review adapter before upgrading')
    main = "import { useConsoleAvatar } from './avatar-hook.js';\n" + re.sub(
        matcher, r'const \1=useConsoleAvatar(\2)??\2.idTokenParsed?.picture;', main)
    # Replace only verified generated assets, never other theme files.
    if generated.exists():
        shutil.rmtree(generated)
    destination = generated / digest
    for name in archive.namelist():
        if not name.startswith(prefix + 'assets/') or name.endswith(('/', '.map')):
            continue
        relative = Path(name[len(prefix):])
        target = (destination / relative).resolve()
        if not target.is_relative_to(destination.resolve()):
            raise ValueError('Unsafe upstream asset path')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(main.encode('utf-8') if str(relative).replace('\\', '/') == entry else archive.read(name))
    (destination / 'assets/avatar-hook.js').write_bytes(hook)
    for chunk in manifest.values():
        for key in ('file', 'css', 'assets'):
            if key in chunk:
                value = chunk[key]
                chunk[key] = ([f'avatar-admin/{digest}/{p}' for p in value] if isinstance(value, list)
                              else f'avatar-admin/{digest}/{value}')
    (resources / '.vite').mkdir(exist_ok=True)
    (resources / '.vite/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(f'Built native Keycloak 26.7.3 admin masthead adapter: {digest}')
