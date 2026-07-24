# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['src\\sig_app.py'],
    pathex=[],
    binaries=[],
    datas=[('assets\\ffmpeg.exe', 'assets'), ('assets\\ffplay.exe', 'assets'), ('assets\\appwin.jpg', 'assets'), ('assets\\appwin.png', 'assets'), ('assets\\icon.png', 'assets'), ('assets\\default_nomes.txt', 'assets')],
    hiddenimports=['_cffi_backend', 'websocket'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='sig_quadrado',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['assets\\icon.ico'],
)
