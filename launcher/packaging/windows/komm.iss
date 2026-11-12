; Inno Setup script for the Komm installer.
;
; The installer is a thin wrapper around the install folder the jpack Maven plugin builds
; (target/jpack/image/windows-x64: Komm.exe = the jpack stub, jpack.json, runtime/, app/,
; launcher/). Everything else - updating the client, the launcher, the Java runtime and the stub
; itself - is done by the stub from then on, so this installer never has to run again.
; Called by the release workflow (or by hand, see launcher/README.md) with:
;   /DAppVersion=...    release version (drives the setup filename and the Apps & Features entry)
;   /DImageDir=...      the jpack install folder to package
;   /DOutputDir=...     where to write the setup exe

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef ImageDir
  #define ImageDir "..\..\..\target\jpack\image\windows-x64"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\..\target\installer"
#endif

[Setup]
; Never change AppId: it is how upgrades find and replace an existing install.
AppId={{5EE5B212-EE3B-43E1-8657-5C183E3FDE55}
AppName=Komm
AppVersion={#AppVersion}
AppVerName=Komm {#AppVersion}
AppPublisher=Komm
VersionInfoVersion={#AppVersion}
; Per-user install: no admin prompt, and the folder stays writable for the stub's updates.
PrivilegesRequired=lowest
DefaultDirName={autopf}\Komm
DefaultGroupName=Komm
; Fresh installs may choose the folder; upgrades silently reuse the existing one.
DisableDirPage=auto
DisableProgramGroupPage=yes
DisableWelcomePage=no
WizardStyle=modern
WizardImageFile=wizard-large.bmp
WizardSmallImageFile=wizard-small.bmp
SetupIconFile=..\..\..\src\jpack\icon.ico
UninstallDisplayIcon={app}\Komm.exe
UninstallDisplayName=Komm
OutputDir={#OutputDir}
OutputBaseFilename=Komm-Setup
Compression=lzma2
SolidCompression=yes
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Re-running the installer over an existing (possibly self-updated) install starts from a clean
; slate: the parts below are replaced as a whole, and a half-downloaded update or the folder a
; swap set aside must not outlive the files they belong to. Per-user data (%APPDATA%\Komm) is untouched.
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\launcher"
Type: filesandordirs; Name: "{app}\update"
Type: filesandordirs; Name: "{app}\old"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Komm"; Filename: "{app}\Komm.exe"
Name: "{autodesktop}\Komm"; Filename: "{app}\Komm.exe"; Tasks: desktopicon

[Registry]
; komm:// URL protocol so browser invite links ("Open in Komm app") start Komm.exe (the stub, which
; runs the launcher's update check and hands the link to the client). The launcher also re-registers
; itself on every start, so this mainly guarantees the handler exists before the first run.
Root: HKA; Subkey: "Software\Classes\komm"; ValueType: string; ValueData: "URL:Komm Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\komm"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\komm\DefaultIcon"; ValueType: string; ValueData: """{app}\Komm.exe"",0"
Root: HKA; Subkey: "Software\Classes\komm\shell\open\command"; ValueType: string; ValueData: """{app}\Komm.exe"" ""%1"""

[UninstallDelete]
; Updates add and replace files the installer never saw (new runtime, jpack.log, the update/old
; folders, a stub that was renamed aside): remove the whole install folder, not just what Setup copied.
Type: filesandordirs; Name: "{app}"

[Run]
Filename: "{app}\Komm.exe"; Description: "{cm:LaunchProgram,Komm}"; Flags: nowait postinstall skipifsilent
