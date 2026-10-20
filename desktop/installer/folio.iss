#ifndef AppSource
  #error Supply the verified unpacked application directory with /DAppSource.
#endif
#ifndef BetaOutput
  #error Supply the private output directory with /DBetaOutput.
#endif
#ifndef BetaVersion
  #define BetaVersion "0.3.0-beta.1"
#endif
#ifndef AppIdentity
  #define AppIdentity "com.folio.workspace.beta"
#endif
#ifndef AppLabel
  #define AppLabel "Folio"
#endif
[Setup]
AppId={#AppIdentity}
AppName={#AppLabel}
AppVersion={#BetaVersion}
AppPublisher=Folio
DefaultDirName={localappdata}\Programs\{#AppLabel}
DefaultGroupName={#AppLabel}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22000
WizardStyle=modern dark
DisableProgramGroupPage=yes
DisableWelcomePage=no
OutputDir={#BetaOutput}
OutputBaseFilename=Folio-{#BetaVersion}-Windows-x64
SetupIconFile=..\assets\folio.ico
UninstallDisplayIcon={app}\Folio.exe
Compression=lzma2/fast
SolidCompression=no
DiskSpanning=no
CloseApplications=no
RestartApplications=no
Uninstallable=yes
CreateUninstallRegKey=yes
UninstallDisplayName={#AppLabel} private beta
; Only installed application files are removed. No AppData workspace deletion.
[Files]
Source: "{#AppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: unchecked
[Icons]
Name: "{userprograms}\{#AppLabel}\{#AppLabel}"; Filename: "{app}\Folio.exe"; AppUserModelID: "com.folio.workspace"
Name: "{userdesktop}\{#AppLabel}"; Filename: "{app}\Folio.exe"; AppUserModelID: "com.folio.workspace"; Tasks: desktopicon
[Run]
Filename: "{app}\Folio.exe"; Description: "Open Folio"; Flags: nowait postinstall skipifsilent
[Code]
function FolioIsRunning: Boolean;
var Locator, Services, Processes: Variant;
begin
  Result := True;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Services := Locator.ConnectServer('', 'root\CIMV2');
    Processes := Services.ExecQuery('SELECT ProcessId FROM Win32_Process WHERE Name = ''Folio.exe''');
    Result := Processes.Count > 0;
  except
    { Fail closed instead of risking replacement of a live application's files. }
  end;
end;
function InitializeSetup: Boolean;
begin
  Result := not FolioIsRunning;
  if not Result then SuppressibleMsgBox('Quit Folio using its system tray menu, then run this installer again. Your workspace will be retained.', mbInformation, MB_OK, IDOK);
end;
function InitializeUninstall: Boolean;
begin
  Result := not FolioIsRunning;
  if not Result then SuppressibleMsgBox('Quit Folio using its system tray menu before uninstalling. Your workspace and documents will be retained.', mbInformation, MB_OK, IDOK);
end;
