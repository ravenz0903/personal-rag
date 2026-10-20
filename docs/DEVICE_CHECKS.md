# Remaining laptop checks

Use this procedure when you are available. It temporarily disconnects the internet and puts the laptop to sleep. Partial observations were recorded on 14 October: an offline task survived relaunch and a document save was reported; the wake reminder appeared once, but clicking it failed to open the app/task. Package G corrects activation and passes automated checks. Human click retesting and the remaining offline observations are still pending. This procedure does not claim those gates have passed.

The current core build has AI disabled. Automated checks passed; isolated installer upgrade/reinstall evidence applies to package F. The corrected package G still needs a refreshed matching installer and physical acceptance. These generated QA packages are local ignored artifacts, not supplied by a fresh clone.

## Start a disposable workspace

Quit any test Folio window using its tray menu, then run these commands in PowerShell. They create a new temporary workspace and launch the verified package with an explicit data directory.

```powershell
$deviceCheckData = Join-Path ([IO.Path]::GetTempPath()) ('folio-device-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $deviceCheckData | Out-Null
Set-Content -LiteralPath (Join-Path $deviceCheckData 'offline-check.md') -Value '# Offline fixture: local storage test' -Encoding utf8
Write-Output $deviceCheckData
& '.\target\app-core-qa-20261014g\win-unpacked\Folio.exe' "--user-data-dir=$deviceCheckData"
```

In Settings, use **Open data folder** and confirm it opens the printed temporary directory before creating records. Your normal Folio workspace is separate.

## Internet disconnected

1. Disconnect Wi-Fi and any wired/mobile internet connection using your normal Windows controls. Keep the laptop awake.
2. Create and approve an Academics task named **Offline device task**. Create a note containing **Offline device note** and wait for **Saved**.
3. Open Documents, explicitly choose Academics, upload the temporary `offline-check.md`, and download its original. Confirm its text is unchanged.
4. Quit Folio from the tray. Run the final launch command above again with the same `$deviceCheckData`. Confirm the task, note and document remain available while internet is disconnected.
5. Restore your usual connection. Report any error and whether each save/reopen check passed.

## Sleep and wake

1. Create and approve an Academics task named **Sleep device task**. Set a reminder roughly three minutes ahead. Leave Folio running; closing its window should keep its tray process active.
2. Put the laptop to sleep using your normal Windows action. Wake it after the reminder time has passed.
3. Check for the reminder within about 20 seconds. If Windows hides banners, inspect Notification Centre. A **Sent to Windows** status alone does not prove visible delivery.
4. Click the actual notification and confirm it opens the editor for **Sleep device task**. Observe for about 40 seconds and report whether duplicate notifications appear.
5. Quit the test app from its tray. Keep the temporary folder if a check failed so logs and records can be inspected.

Report: offline save/reopen results, reminder visible after wake, correct editor on click, and duplicate notifications yes/no. These are human device observations; they must not be inferred from the automated tests.
