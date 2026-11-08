; Inno Setup script for the Komm installer.
; Compiled by the launcher's `installer` Maven profile, which first builds the
; jpackage app-image (launcher exe + private runtime, no client jar inside it)
; and then passes these defines:
;   /DAppVersion=...    client version bundled as the seed (drives the setup filename)
;   /DAppImageDir=...   the jpackage app-image directory to package
;   /DOutputDir=...     where to write the setup exe
;   /DClientJar=...     the client fat jar to seed into %APPDATA%/Komm/bin/komm.jar

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef AppImageDir
  #define AppImageDir "..\..\target\jpackage\Komm"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\target\installer"
#endif
#ifndef ClientJar
  #define ClientJar "..\..\..\komm\target\komm-0.0.1.jar"
#endif

[Setup]
; Never change AppId: it is how upgrades find and replace an existing install.
AppId={{5EE5B212-EE3B-43E1-8657-5C183E3FDE55}
AppName=Komm
AppVersion={#AppVersion}
AppVerName=Komm {#AppVersion}
AppPublisher=Komm
VersionInfoVersion={#AppVersion}
; Per-user install: no admin prompt, matches the HKCU komm:// registration.
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
SetupIconFile=komm.ico
UninstallDisplayIcon={app}\Komm.exe
UninstallDisplayName=Komm
OutputDir={#OutputDir}
OutputBaseFilename=Komm-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; The client jar itself is handled below (Files, ignoreversion — always
; overwritten, not deleted-then-reseeded). These are leftovers the jar swap
; itself can't clean up: a partial download, the legacy version.txt, and
; extracted JNativeHook natives (re-extracted by the client on next start).
Type: files; Name: "{userappdata}\Komm\bin\komm.jar.download"
Type: files; Name: "{userappdata}\Komm\bin\version.txt"
Type: files; Name: "{userappdata}\Komm\bin\JNativeHook-*.dll"

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
; Seeds the client straight into the user-data bin dir so the first start
; works offline — no komm-client-seed.jar detour through the app-image and no
; BundledClientSeeder copy-on-first-launch step (Windows only; the AppImage
; still needs that, since it has no separate install phase to hook into).
; Unconditionally overwritten on every (re)install — same repair-a-corrupt-jar
; behavior the old delete-then-reseed dance had, just in one step now. The
; normal GitHub-update flow takes over from the first launch either way.
Source: "{#ClientJar}"; DestDir: "{userappdata}\Komm\bin"; DestName: "komm.jar"; Flags: ignoreversion

[Icons]
Name: "{group}\Komm"; Filename: "{app}\Komm.exe"
Name: "{autodesktop}\Komm"; Filename: "{app}\Komm.exe"; Tasks: desktopicon

[Registry]
; komm:// URL protocol so browser invite links ("Open in Komm app") start the
; launcher. The launcher also re-registers itself on every start, so this mainly
; guarantees the handler exists before the first run.
Root: HKA; Subkey: "Software\Classes\komm"; ValueType: string; ValueData: "URL:Komm Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\komm"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\komm\DefaultIcon"; ValueType: string; ValueData: """{app}\Komm.exe"",0"
Root: HKA; Subkey: "Software\Classes\komm\shell\open\command"; ValueType: string; ValueData: """{app}\Komm.exe"" ""%1"""

[Run]
Filename: "{app}\Komm.exe"; Description: "{cm:LaunchProgram,Komm}"; Flags: nowait postinstall skipifsilent
