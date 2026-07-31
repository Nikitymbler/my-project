#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root 'SETUP_LOCAL_OVERLAY_HTTPS.cmd'))) {
    $root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}

Write-Host ('Validating PowerShell scripts under: ' + $root)
$files = Get-ChildItem -LiteralPath $root -Recurse -Filter '*.ps1' -File -ErrorAction Stop |
    Where-Object {
        $_.FullName -notmatch '\\(\.git|build|\.gradle|run)\\'
    }

if (-not $files -or $files.Count -eq 0) {
    throw 'No .ps1 files found for validation.'
}

$totalErrors = 0
foreach ($file in $files) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
    $count = 0
    if ($errors) {
        $count = $errors.Count
    }
    Write-Host ($file.FullName + ' => errors=' + $count)
    if ($count -gt 0) {
        $totalErrors += $count
        foreach ($err in $errors) {
            Write-Host ('  L' + $err.Extent.StartLineNumber + 'C' + $err.Extent.StartColumnNumber + ': ' + $err.Message) -ForegroundColor Red
        }
    }

    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    $hasBom = ($bytes.Length -ge 3) -and ($bytes[0] -eq 0xEF) -and ($bytes[1] -eq 0xBB) -and ($bytes[2] -eq 0xBF)
    if (($file.Name -eq 'Setup-LocalOverlayHttps.ps1' -or $file.Name -eq 'OverlaySetupResult.ps1') -and (-not $hasBom)) {
        Write-Host ('  ERROR: ' + $file.Name + ' must be UTF-8 with BOM') -ForegroundColor Red
        $totalErrors += 1
    }
}

if ($totalErrors -gt 0) {
    Write-Host ('FAIL: total parser/encoding issues=' + $totalErrors) -ForegroundColor Red
    exit 1
}

Write-Host 'PASS: all PowerShell scripts parsed successfully.' -ForegroundColor Green
exit 0
