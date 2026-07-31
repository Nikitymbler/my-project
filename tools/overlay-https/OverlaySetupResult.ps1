#Requires -Version 5.1
# Shared result helpers for Arena local HTTPS overlay setup (no secrets).
# Dot-sourced by Setup-LocalOverlayHttps.ps1 and unit tests.

Set-StrictMode -Version Latest

$script:OverlayCtrlCExitCode = -1073741510  # 0xC000013A STATUS_CONTROL_C_EXIT

function Get-OverlaySetupResultPath {
    param([string]$RuntimeDir)
    return (Join-Path $RuntimeDir 'setup-result.json')
}

function ConvertTo-OverlayJsonBool {
    param([bool]$Value)
    return $Value.ToString().ToLowerInvariant()
}

function Escape-OverlayJsonString {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return $null
    }
    $safe = $Value -replace '\\', '\\' -replace '"', '\"' -replace "`r", ' ' -replace "`n", ' '
    return $safe
}

function Save-OverlaySetupResultAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$ResultPath,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][bool]$Success,
        [bool]$HostsValid = $false,
        [bool]$RootCertificateValid = $false,
        [bool]$ServerCertificateValid = $false,
        [bool]$SanValid = $false,
        [bool]$Pkcs12Valid = $false,
        [AllowNull()][string]$FailedStep = $null,
        [AllowNull()][string]$ErrorMessage = $null,
        [int]$ProcessExitCode = 1
    )

    $dir = Split-Path -Parent $ResultPath
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }

    if ($null -eq $FailedStep -or $FailedStep -eq '') {
        $failedJson = 'null'
    } else {
        $failedJson = '"' + (Escape-OverlayJsonString -Value $FailedStep) + '"'
    }
    if ($null -eq $ErrorMessage -or $ErrorMessage -eq '') {
        $errorJson = 'null'
    } else {
        $errorJson = '"' + (Escape-OverlayJsonString -Value $ErrorMessage) + '"'
    }

    $json = @(
        '{',
        ('  "runId": "' + (Escape-OverlayJsonString -Value $RunId) + '",'),
        ('  "success": ' + (ConvertTo-OverlayJsonBool -Value $Success) + ','),
        ('  "timestamp": "' + (Get-Date).ToUniversalTime().ToString('o') + '",'),
        ('  "hostsValid": ' + (ConvertTo-OverlayJsonBool -Value $HostsValid) + ','),
        ('  "rootCertificateValid": ' + (ConvertTo-OverlayJsonBool -Value $RootCertificateValid) + ','),
        ('  "serverCertificateValid": ' + (ConvertTo-OverlayJsonBool -Value $ServerCertificateValid) + ','),
        ('  "sanValid": ' + (ConvertTo-OverlayJsonBool -Value $SanValid) + ','),
        ('  "pkcs12Valid": ' + (ConvertTo-OverlayJsonBool -Value $Pkcs12Valid) + ','),
        ('  "failedStep": ' + $failedJson + ','),
        ('  "error": ' + $errorJson + ','),
        ('  "processExitCode": ' + $ProcessExitCode),
        '}'
    ) -join "`r`n"

    $tmpPath = $ResultPath + '.tmp'
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($tmpPath, $json, $utf8NoBom)

    if (Test-Path -LiteralPath $ResultPath) {
        Remove-Item -LiteralPath $ResultPath -Force
    }
    Move-Item -LiteralPath $tmpPath -Destination $ResultPath -Force
}

function Read-OverlaySetupResult {
    param([Parameter(Mandatory = $true)][string]$ResultPath)

    if (-not (Test-Path -LiteralPath $ResultPath)) {
        return $null
    }

    try {
        $raw = [System.IO.File]::ReadAllText($ResultPath)
        if ([string]::IsNullOrWhiteSpace($raw)) {
            return $null
        }
        return ($raw | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Test-OverlayResultFlagsSuccess {
    param($Result)

    if ($null -eq $Result) {
        return $false
    }
    if (-not [bool]$Result.success) {
        return $false
    }
    # hostsValid is optional for localhost primary mode
    if (-not [bool]$Result.rootCertificateValid) {
        return $false
    }
    if (-not [bool]$Result.serverCertificateValid) {
        return $false
    }
    if (-not [bool]$Result.sanValid) {
        return $false
    }
    if (-not [bool]$Result.pkcs12Valid) {
        return $false
    }
    return $true
}

function Resolve-OverlayParentOutcome {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessExitCode,
        [Parameter(Mandatory = $true)][string]$ExpectedRunId,
        [Parameter(Mandatory = $true)][string]$ResultPath
    )

    $jsonFound = Test-Path -LiteralPath $ResultPath
    $result = $null
    $parseOk = $false
    $runIdValid = $false

    if ($jsonFound) {
        $result = Read-OverlaySetupResult -ResultPath $ResultPath
        if ($null -ne $result) {
            $parseOk = $true
            if ($null -ne $result.runId -and [string]$result.runId -eq [string]$ExpectedRunId) {
                $runIdValid = $true
            }
        }
    }

    $flagsOk = $parseOk -and $runIdValid -and (Test-OverlayResultFlagsSuccess -Result $result)
    $interrupted = ($ProcessExitCode -eq $script:OverlayCtrlCExitCode)

    if ($flagsOk) {
        $warning = $null
        if ($interrupted -or (($ProcessExitCode -ne 0) -and ($ProcessExitCode -ne $script:OverlayCtrlCExitCode))) {
            if ($interrupted) {
                $warning = 'Административная консоль завершилась нестандартным кодом, но установка и финальная проверка успешно завершены.'
            } elseif ($ProcessExitCode -ne 0) {
                $warning = ('Административный процесс вернул код ' + $ProcessExitCode + ', но setup-result.json текущего запуска подтверждает успех.')
            }
        }
        return [pscustomobject]@{
            Success            = $true
            FinalExitCode      = 0
            JsonFound          = $jsonFound
            ParseOk            = $parseOk
            RunIdValid         = $runIdValid
            InterruptedExit    = $interrupted
            ProcessExitCode    = $ProcessExitCode
            Warning            = $warning
            FailedStep         = $null
            ErrorMessage       = $null
            Result             = $result
        }
    }

    $failedStep = $null
    $errorMessage = $null
    if (-not $jsonFound) {
        $errorMessage = 'setup-result.json не найден после elevated-процесса.'
        $failedStep = 'result_json_missing'
    } elseif (-not $parseOk) {
        $errorMessage = 'setup-result.json повреждён или нечитаем.'
        $failedStep = 'result_json_corrupt'
    } elseif (-not $runIdValid) {
        $errorMessage = 'runId в setup-result.json не совпадает с текущим запуском (устаревший или чужой результат).'
        $failedStep = 'result_runid_mismatch'
    } elseif ($null -ne $result) {
        $failedStep = $result.failedStep
        $errorMessage = $result.error
        if ([string]::IsNullOrWhiteSpace([string]$errorMessage)) {
            $errorMessage = 'setup-result.json не подтверждает полный успех установки.'
        }
    } else {
        $errorMessage = 'Не удалось подтвердить результат установки.'
        $failedStep = 'result_unknown'
    }

    return [pscustomobject]@{
        Success            = $false
        FinalExitCode      = 1
        JsonFound          = $jsonFound
        ParseOk            = $parseOk
        RunIdValid         = $runIdValid
        InterruptedExit    = $interrupted
        ProcessExitCode    = $ProcessExitCode
        Warning            = $null
        FailedStep         = $failedStep
        ErrorMessage       = $errorMessage
        Result             = $result
    }
}
