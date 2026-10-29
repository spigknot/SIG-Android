"""Build an immutable native update replacing only Whisper in an authenticated base."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--version', type=int, required=True)
    parser.add_argument('--base-version', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.version > args.base_version
    contract = (args.root / 'app/src/main/java/br/gov/sp/pcsp/launcher/NativeDependencyManager.kt').read_text(encoding='utf-8')
    assert re.search(r'COMPONENT_VERSION\s*=\s*"' + str(args.base_version) + '"', contract), 'Base must match current published contract'
    args.output.mkdir(parents=True, exist_ok=True)
    packages = []
    prepared = []
    for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
        base = args.root / f'native-dependencies/build/sig-android-dependencies-v{args.base_version}-{abi}.zip'
        base_bytes = base.read_bytes()
        assert digest(base_bytes) in contract, f'Base SHA not authenticated: {abi}'
        library = args.root / f'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/{abi}/libsig_whisper.so'
        payload = library.read_bytes()
        assert payload[:4] == b'\x7fELF' and int.from_bytes(payload[18:20], 'little') == machine
        target = args.output / f'sig-android-dependencies-v{args.version}-{abi}.zip'
        assert not target.exists(), f'Never overwrite prepared package: {target}'
        with zipfile.ZipFile(base) as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
        manifest = json.loads(files['manifest.json'].decode('utf-8-sig'))
        assert str(manifest['version']) == str(args.base_version) and manifest['abi'] == abi
        for item in manifest['files']:
            data = files[item['path']]
            assert len(data) == item['size'] and digest(data) == item['sha256'], item['path']
        old_whisper = files['lib/libsig_whisper.so']
        assert digest(old_whisper) != digest(payload), 'No Whisper update present'
        files['lib/libsig_whisper.so'] = payload
        manifest['version'] = str(args.version)
        for item in manifest['files']:
            if item['path'] == 'lib/libsig_whisper.so':
                item.update(size=len(payload), sha256=digest(payload))
        files['manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        prepared.append((abi, target, files, digest(base_bytes), digest(payload)))
    for abi, target, files, base_sha, whisper_sha in prepared:
        with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        with zipfile.ZipFile(target) as archive:
            assert archive.testzip() is None
            assert all(archive.read(name) == data for name, data in files.items())
        packages.append(dict(version=str(args.version), abi=abi, file=target.name,
                             size=target.stat().st_size, sha256=digest(target.read_bytes()),
                             base_sha256=base_sha, whisper_sha256=whisper_sha))
    index = dict(format=1, componentVersion=str(args.version), baseVersion=str(args.base_version), packages=packages)
    (args.output / 'packages.json').write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(index, indent=2))


if __name__ == '__main__':
    main()
