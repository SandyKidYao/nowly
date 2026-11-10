#!/usr/bin/env python3
"""Write site/release.json, which the product page and the app's update check both read.

Reads RELEASE_TAG, VERSION_CODE, APK_SHA256 and APK_SIZE from the environment and the
release notes from the file given as the only argument. Field names and limits must
match UpdateManifest in app/src/main/kotlin/com/nowly/app/updates/ReleaseInfo.kt.
"""
import json
import os
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
tag = os.environ['RELEASE_TAG']
assert re.fullmatch(r'v\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?', tag), f'Unexpected tag: {tag}'
version_code = int(os.environ['VERSION_CODE'])
assert version_code > 0, 'versionCode must be positive'
sha256 = os.environ['APK_SHA256']
assert re.fullmatch(r'[0-9a-f]{64}', sha256), 'APK checksum must be 64 lowercase hex digits'
size = int(os.environ['APK_SIZE'])
assert 0 < size <= 200 * 1024 * 1024, f'Unexpected APK size: {size}'
notes = Path(sys.argv[1]).read_text(encoding='utf-8').replace('\r\n', '\n').strip()

manifest = {
    'tag': tag,
    'versionCode': version_code,
    'versionName': tag[1:],
    'apkUrl': f'https://github.com/SandyKidYao/nowly/releases/download/{tag}/nowly.apk',
    'sha256': sha256,
    'size': size,
    'notes': notes[:2000],
}
(root / 'site/release.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'release.json: {tag} (versionCode {version_code}, {size} bytes)')
