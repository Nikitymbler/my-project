#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$helper = Join-Path $PSScriptRoot 'OverlaySetupResult.ps1'
. $helper

$failed = 0
function Assert-True([bool]$Condition, [string]$Name) {
    if ($Condition) {
        Write-Host ("PASS: " + $Name) -ForegroundColor Green
    } else {
        Write-Host ("FAIL: " + $Name) -ForegroundColor Red
        $script:failed++
    }
}

$tempRoot = Join-Path $env:TEMP ('arena-overlay-result-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$resultPath = Join-Path $tempRoot 'setup-result.json'
$runId = [guid]::NewGuid().ToString('N')

try {
    # 4. ExitCode=0 + success JSON
    Save-OverlaySetupResultAtomic -ResultPath $resultPath -RunId $runId -Success $true `
        -HostsValid $true -RootCertificateValid $true -ServerCertificateValid $true -SanValid $true -Pkcs12Valid $true `
        -ProcessExitCode 0
    $o = Resolve-OverlayParentOutcome -ProcessExitCode 0 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $true) 'ExitCode=0 + success JSON => SUCCESS'

    # 5. ExitCode=1 + failed JSON
    Save-OverlaySetupResultAtomic -ResultPath $resultPath -RunId $runId -Success $false `
        -FailedStep 'hosts' -ErrorMessage 'boom' -ProcessExitCode 1
    $o = Resolve-OverlayParentOutcome -ProcessExitCode 1 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $false) 'ExitCode=1 + failed JSON => FAILED'

    # 6. ExitCode=-1073741510 + success JSON current runId
    Save-OverlaySetupResultAtomic -ResultPath $resultPath -RunId $runId -Success $true `
        -HostsValid $true -RootCertificateValid $true -ServerCertificateValid $true -SanValid $true -Pkcs12Valid $true `
        -ProcessExitCode 0
    $o = Resolve-OverlayParentOutcome -ProcessExitCode -1073741510 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $true) 'Interrupted exit + fresh success JSON => SUCCESS'
    Assert-True ($null -ne $o.Warning -and $o.Warning.Length -gt 0) 'Interrupted success includes warning'

    # 7. ExitCode=-1073741510 + missing JSON
    Remove-Item -LiteralPath $resultPath -Force -ErrorAction SilentlyContinue
    $o = Resolve-OverlayParentOutcome -ProcessExitCode -1073741510 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $false) 'Interrupted exit + no JSON => FAILED'

    # 8. Stale JSON other runId
    Save-OverlaySetupResultAtomic -ResultPath $resultPath -RunId 'oldrunid' -Success $true `
        -HostsValid $true -RootCertificateValid $true -ServerCertificateValid $true -SanValid $true -Pkcs12Valid $true `
        -ProcessExitCode 0
    $o = Resolve-OverlayParentOutcome -ProcessExitCode -1073741510 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $false) 'Stale JSON different runId => FAILED'
    Assert-True ($o.RunIdValid -eq $false) 'Stale JSON runId invalid'

    # 9. Corrupt JSON
    [System.IO.File]::WriteAllText($resultPath, '{ not-json', (New-Object System.Text.UTF8Encoding $false))
    $o = Resolve-OverlayParentOutcome -ProcessExitCode 0 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $false) 'Corrupt JSON => FAILED'

    # 10. Partial/tmp only (no final json)
    Remove-Item -LiteralPath $resultPath -Force -ErrorAction SilentlyContinue
    $tmp = $resultPath + '.tmp'
    [System.IO.File]::WriteAllText($tmp, '{"success":true}', (New-Object System.Text.UTF8Encoding $false))
    $o = Resolve-OverlayParentOutcome -ProcessExitCode 0 -ExpectedRunId $runId -ResultPath $resultPath
    Assert-True ($o.Success -eq $false) 'Only .tmp present => FAILED'
    Assert-True ($o.JsonFound -eq $false) 'Final JSON not found when only tmp exists'

    # Atomic write replaces tmp
    Save-OverlaySetupResultAtomic -ResultPath $resultPath -RunId $runId -Success $true `
        -HostsValid $true -RootCertificateValid $true -ServerCertificateValid $true -SanValid $true -Pkcs12Valid $true `
        -ProcessExitCode 0
    Assert-True (Test-Path -LiteralPath $resultPath) 'Atomic write creates final JSON'
    Assert-True (-not (Test-Path -LiteralPath ($resultPath + '.tmp'))) 'Atomic write removes/moves tmp'
    $parsed = Read-OverlaySetupResult -ResultPath $resultPath
    Assert-True ($parsed.runId -eq $runId) 'Atomic JSON contains runId'
}
finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failed -gt 0) {
    Write-Host ("FAILED tests: " + $failed) -ForegroundColor Red
    exit 1
}
Write-Host 'All OverlaySetupResult tests passed.' -ForegroundColor Green
exit 0
