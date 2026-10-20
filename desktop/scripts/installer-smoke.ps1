param([string]$UpgradeVersion='0.2.1-core-qa.2',[string]$SourceFolder='target/app-core-qa-20261014d/win-unpacked',[string]$ResultName='app-installer-acceptance-20261014')
$ErrorActionPreference='Stop'
if ($UpgradeVersion -notmatch '^0\.2\.1-core-qa\.[0-9]+$' -or $ResultName -notmatch '^app-installer-[a-z0-9-]+$') { throw 'Use declared QA version/result names.' }
$qaRepoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaInstallerFolder=Join-Path $qaRepoRoot 'target/core-installer-qa-20261014'
$qaInstaller1=Join-Path $qaInstallerFolder 'Folio-0.2.1-core-qa.1-Windows-x64.exe'
$qaInstaller2=Join-Path $qaInstallerFolder ('Folio-'+$UpgradeVersion+'-Windows-x64.exe')
$qaRegistry='HKCU:/Software/Microsoft/Windows/CurrentVersion/Uninstall/com.folio.workspace.qa.20261014_is1'
if (Test-Path -LiteralPath $qaRegistry) { throw 'Existing QA installation must be inspected before starting another test.' }
foreach ($qaInstaller in @($qaInstaller1,$qaInstaller2)) { if (-not (Test-Path -LiteralPath $qaInstaller)) { throw 'Both QA installer versions must be compiled first.' } }
$qaTemporaryRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$qaRoot=[IO.Path]::GetFullPath((Join-Path $qaTemporaryRoot ('folio-installer-qa-'+[guid]::NewGuid().ToString('N'))))
if (-not $qaRoot.StartsWith($qaTemporaryRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid temporary fixture root.' }
New-Item -ItemType Directory -Path $qaRoot | Out-Null
$qaInstall=Join-Path $qaRoot 'app';$qaWorkspace=Join-Path $qaRoot 'workspace'
New-Item -ItemType Directory -Path $qaWorkspace | Out-Null
@{fixture='folio-installer-qa'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $qaWorkspace 'qa-workspace-marker.json') -Encoding UTF8
$qaStatePath=Join-Path $qaRepoRoot ('target/'+$ResultName+'.json')
$qaState=[ordered]@{status='running';phase='install';root=$qaRoot;install=$qaInstall;workspace=$qaWorkspace;registry=$qaRegistry;completed=@();core_only=$true;ai_enabled=$false;training_paused=$true}
function Save-QAState { $qaState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $qaStatePath -Encoding UTF8 }
function Assert-QAPath([string]$value) { $qaAbsolute=[IO.Path]::GetFullPath($value);if (-not $qaAbsolute.StartsWith($qaRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Operation outside QA fixture refused.' } }
function Assert-QAStopped {
    $qaDeadline=(Get-Date).AddSeconds(25)
    do { $qaProcesses=@(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('Folio.exe','java.exe') -and $_.CommandLine -and $_.CommandLine.Contains($qaInstall) });if (-not $qaProcesses.Count) { return };Start-Sleep -Milliseconds 200 } while ((Get-Date) -lt $qaDeadline)
    throw 'QA executable/backend still running.'
}
function Install-QA([string]$installer,[string]$phase) {
    Assert-QAPath $qaInstall;Assert-QAStopped
    $qaLog=Join-Path $qaRoot ($phase+'-setup.log')
    $qaProcess=Start-Process -FilePath $installer -WindowStyle Hidden -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CURRENTUSER','/TASKS=',('/DIR="'+$qaInstall+'"'),('/LOG="'+$qaLog+'"')) -Wait -PassThru
    if ($qaProcess.ExitCode -ne 0) { throw ($phase+' installer failed: '+$qaProcess.ExitCode) }
    if (-not (Test-Path -LiteralPath (Join-Path $qaInstall 'Folio.exe'))) { throw 'Installed executable missing.' }
    $qaEntry=Get-ItemProperty -LiteralPath $qaRegistry
    if ([IO.Path]::GetFullPath($qaEntry.InstallLocation).TrimEnd('\') -ne $qaInstall) { throw 'QA registry points outside intended installation.' }
}
function Uninstall-QA([string]$phase) {
    Assert-QAStopped;$qaUninstaller=Join-Path $qaInstall 'unins000.exe';Assert-QAPath $qaUninstaller
    $qaLog=Join-Path $qaRoot ($phase+'-uninstall.log')
    $qaProcess=Start-Process -FilePath $qaUninstaller -WindowStyle Hidden -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/LOG="'+$qaLog+'"')) -Wait -PassThru
    if ($qaProcess.ExitCode -ne 0) { throw ($phase+' uninstall failed: '+$qaProcess.ExitCode) }
    if ((Test-Path -LiteralPath (Join-Path $qaInstall 'Folio.exe')) -or (Test-Path -LiteralPath $qaRegistry)) { throw 'QA executable or uninstall registration remains.' }
}
function Smoke-QA([string]$phase) {
    $qaLog=Join-Path $qaRoot ($phase+'-application.log')
    & node (Join-Path $PSScriptRoot 'packaged-app-smoke.cjs') (Join-Path $qaInstall 'Folio.exe') $qaWorkspace *> $qaLog
    if ($LASTEXITCODE -ne 0) { throw ($phase+' installed application failed; inspect '+$qaLog) }
    Assert-QAStopped
}
function Snapshot-QAData {
    Assert-QAStopped;$qaHashes=[ordered]@{}
    foreach ($qaDataFolder in @('data','note-drafts')) { $qaFolder=Join-Path $qaWorkspace $qaDataFolder;if (Test-Path -LiteralPath $qaFolder) { Get-ChildItem -LiteralPath $qaFolder -File -Recurse | Sort-Object FullName | ForEach-Object { $qaRelative=[IO.Path]::GetRelativePath($qaWorkspace,$_.FullName);$qaHashes[$qaRelative]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } } }
    return ($qaHashes | ConvertTo-Json -Compress)
}
try {
    Save-QAState;Install-QA $qaInstaller1 'initial';Smoke-QA 'initial';$qaState.completed+=@('initial_install','installed_startup_save_restart');$qaState.phase='upgrade';Save-QAState
    $qaBefore=Snapshot-QAData
    Add-Content -LiteralPath (Join-Path $qaInstall 'resources/frontend/index.html') -Value '<!-- QA replacement marker -->'
    Install-QA $qaInstaller2 'upgrade'
    if ((Snapshot-QAData) -ne $qaBefore) { throw 'Upgrade changed workspace database/documents.' }
    if ((Get-ItemProperty -LiteralPath $qaRegistry).DisplayVersion -ne $UpgradeVersion) { throw 'Upgrade registration version did not change.' }
    $qaExpected=(Get-FileHash -LiteralPath (Join-Path $qaRepoRoot ($SourceFolder+'/resources/frontend/index.html'))).Hash
    if ((Get-FileHash -LiteralPath (Join-Path $qaInstall 'resources/frontend/index.html')).Hash -ne $qaExpected) { throw 'Upgrade did not replace modified application asset.' }
    Smoke-QA 'upgraded';$qaState.completed+=@('upgrade_preserves_file_checksums','upgrade_replaces_application_asset','upgraded_records_preserved');$qaState.phase='uninstall_reinstall';Save-QAState
    if ([int]$UpgradeVersion.Split('.')[-1] -ge 4) {
        & node -e "const fs=require('node:fs'),path=require('node:path');const {NoteDrafts}=require(process.argv[1]);const root=process.argv[2],note=JSON.parse(fs.readFileSync(path.join(root,'qa-record.json'),'utf8')).note;new NoteDrafts(root).set(note.id,JSON.stringify({...note,title:'Installer retained recovery draft'}));" (Join-Path $PSScriptRoot '../note-drafts.cjs') $qaWorkspace
        if ($LASTEXITCODE -ne 0) { throw 'Recovery draft fixture failed.' }
    }
    $qaBefore=Snapshot-QAData;Uninstall-QA 'first'
    if ((Snapshot-QAData) -ne $qaBefore) { throw 'Uninstall changed database/documents.' }
    Install-QA $qaInstaller2 'reinstall'
    if ((Snapshot-QAData) -ne $qaBefore) { throw 'Reinstall changed database/documents.' }
    Smoke-QA 'reinstalled';$qaState.completed+=@('uninstall_preserves_file_checksums','reinstall_preserves_file_checksums','reinstalled_records_preserved');$qaState.phase='cleanup';Save-QAState
    $qaBefore=Snapshot-QAData;Uninstall-QA 'cleanup';if ((Snapshot-QAData) -ne $qaBefore) { throw 'Cleanup changed saved data.' }
    $qaState.completed+=@('qa_installation_removed');$qaState.phase='completed';$qaState.status='passed';Save-QAState
    Write-Output ('PASS: isolated installer, upgrade, uninstall/reinstall, installed UI persistence and database/document checksum preservation. Fixture: '+$qaRoot)
} catch { $qaState.status='failed';$qaState.error=$_.Exception.Message;Save-QAState;throw }
