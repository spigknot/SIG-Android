#!/usr/bin/env python3
"""SIG — checagem estatica dos scripts PowerShell e do CI.

A. funcao propria (Verbo-Substantivo) chamada mas nao definida no arquivo nem em lib dot-sourced
B. param() declarado e nunca usado no corpo
C. dot-source de arquivo inexistente
D. workflows/CI referenciando script que nao existe
"""
import re, pathlib, subprocess, collections
ROOT = pathlib.Path(__file__).resolve().parents[2]
tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split()
PS = [ROOT / f for f in tracked if f.endswith(".ps1") and "/cpp/" not in f]
R = collections.defaultdict(list)
def add(k, v): R[k].append(v)

DEF_RE = re.compile(r"(?im)^\s*function\s+([A-Za-z][\w-]*)")
DOT_RE = re.compile(r'(?im)^\s*\.\s*["\']?([^"\'\s]+\.ps1)["\']?')
CALL_RE = re.compile(r"(?m)(?<![\w-])([A-Z][a-z]+-[A-Z][\w-]*)\b")
PARAM_RE = re.compile(r"(?is)\bparam\s*\((.*?)\)\s*\n")
NAME_RE = re.compile(r"^\s*\$(\w+)\s*(?:=|,|$)", re.M)
BUILTIN = {"Write-Host", "Write-Error", "Write-Warning", "Write-Output", "Write-Verbose",
           "Write-Debug", "Get-Content", "Set-Content", "Join-Path", "Split-Path", "Test-Path",
           "Get-ChildItem", "New-Item", "Remove-Item", "Copy-Item", "Move-Item", "Select-Object",
           "Where-Object", "ForEach-Object", "Sort-Object", "Group-Object", "Measure-Object",
           "Get-Item", "Set-Item", "Out-File", "Start-Process", "Stop-Process", "Get-Process",
           "New-Object", "Add-Content", "Get-Command", "Get-Module", "Import-Module", "Push-Location",
           "Pop-Location", "Set-Location", "Get-Location", "ConvertTo-Json", "ConvertFrom-Json",
           "Invoke-WebRequest", "Invoke-RestMethod", "Expand-Archive", "Compress-Archive", "Get-Date",
           "Get-FileHash", "Resolve-Path", "Get-Random", "Start-Sleep", "Read-Host", "Get-Help",
           "Add-Type", "Select-String", "Compare-Object", "New-ItemProperty", "Get-ItemProperty",
           "Set-StrictMode", "Get-Member", "Get-Variable", "New-Variable", "Remove-Variable",
           "Test-PathVariable", "Wait-Process", "Get-Error", "Get-ComputerInfo", "Get-CimInstance",
           "Get-WmiObject", "New-Guid", "Get-Content", "Rename-Item", "Get-Acl", "Set-Acl",
           "Out-String", "Tee-Object", "Clear-Content", "New-TimeSpan", "Format-Table", "Get-Volume",
           "Get-PSDrive", "Split-Path", "Get-NetFirewallRule", "New-NetFirewallRule", "Get-Service",
           "Test-NetConnection", "Start-Job", "Wait-Job", "Receive-Job", "Remove-Job", "Get-Job",
           "New-SmbShare", "Get-SmbShare", "Set-SmbShare", "Grant-SmbShareAccess", "Get-LocalUser",
           "New-LocalUser", "Add-LocalGroupMember", "Get-LocalGroupMember", "Test-Connection",
           "Invoke-Expression", "Get-Unique", "Read-Error", "Out-Null", "Where-Object", "Format-Hex",
           "Set-Content", "Get-ScheduledTask", "Register-ScheduledTask", "Unregister-ScheduledTask",
           "Get-ItemPropertyValue", "New-PSDrive", "Get-Registry"}

for p in PS:
    txt = p.read_text(encoding="utf-8", errors="replace")
    defined = set(DEF_RE.findall(txt))
    dotted = set()
    for d in DOT_RE.findall(txt):
        f = (p.parent / d.replace("\\", "/").replace("$PSScriptRoot", ".")).resolve()
        if not f.exists():
            f2 = (ROOT / d.lstrip("./")).resolve()
            add("C_dotsource_inexistente", f"{p.relative_to(ROOT)} -> dot-source '{d}'")
            if not f2.exists():
                continue
            f = f2
        dotted |= set(DEF_RE.findall(f.read_text(encoding="utf-8", errors="replace")))
    body = re.sub(r"(?m)^\s*#.*$", "", txt)
    for call in sorted(set(CALL_RE.findall(body))):
        if call in BUILTIN or call in defined or call in dotted:
            continue
        add("A_funcao_nao_definida", f"{p.relative_to(ROOT)} chama {call}")
    m = PARAM_RE.search(txt)
    if m:
        for pname in NAME_RE.findall(m.group(1)):
            if len(re.findall(r"\$" + re.escape(pname) + r"\b", txt)) <= 1:
                add("B_param_nao_usado", f"{p.relative_to(ROOT)}: \${pname}")

WF = ROOT / ".github/workflows"
for p in list(WF.glob("*.yml")) + list(WF.glob("*.yaml")):
    txt = p.read_text(encoding="utf-8", errors="replace")
    for ref in re.findall(r"([\w./-]+\.ps1)", txt):
        if not (ROOT / ref.lstrip("./")).exists():
            add("D_workflow_script_inexistente", f"{p.name} -> {ref}")
    for ref in re.findall(r"([\w./-]+\.(?:py|sh|gradle|bat))", txt):
        if ref.startswith(("scripts/", "tools/")) and not (ROOT / ref).exists():
            add("D_workflow_script_inexistente", f"{p.name} -> {ref}")

out = []
for k in sorted(R):
    out.append(f"\n===== {k} ({len(R[k])}) =====")
    out.extend(sorted(set(R[k])))
rep = "\n".join(out) or "(sem findings)"
print(rep)
