import zipfile, shutil, os, json, hashlib

def sha256(path):
    h = hashlib.sha256()
    with open(path,'rb') as f:
        for chunk in iter(lambda: f.read(1<<20), b''):
            h.update(chunk)
    return h.hexdigest()

BS = chr(92)  # backslash
for abi in ['arm64-v8a','x86_64']:
    zip_path = 'native-dependencies/build/sig-android-dependencies-v10-%s.zip' % abi
    staging = '/tmp/v10-stage-%s' % abi
    if os.path.exists(staging):
        shutil.rmtree(staging)
    os.makedirs(staging)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(staging)
    lib_strip = 'native-dependencies/build/llama/%s/libsig_llama.so' % abi
    lib_dst = staging + '/lib/libsig_llama.so'
    shutil.copy2(lib_strip, lib_dst)
    man_path = staging + '/manifest.json'
    with open(man_path, encoding='utf-8-sig') as f:
        man = json.load(f)
    for rec in man['files']:
        if rec['path'] == 'lib/libsig_llama.so':
            rec['size'] = os.path.getsize(lib_dst)
            rec['sha256'] = sha256(lib_dst)
            print('%s: manifest libsig_llama -> %d B sha=%s...' % (abi, rec['size'], rec['sha256'][:16]))
    with open(man_path,'w') as f:
        json.dump(man, f, indent=2)
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as zout:
        for root,dirs,files in os.walk(staging):
            for fn in sorted(files):
                full = os.path.join(root,fn)
                arc = os.path.relpath(full, staging).replace(BS, '/')
                zout.write(full, arc)
    print('%s: ZIP %d B sha=%s' % (abi, os.path.getsize(zip_path), sha256(zip_path)))
print('REPACK_DONE')
