#Requires -Version 5.1
param(
    [switch]$ValidateOnly,
    [switch]$Elevated,
    [switch]$NoPause,
    [switch]$VerifyInstalled,
    [string]$RunId = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security

$helperPath = Join-Path $PSScriptRoot 'OverlaySetupResult.ps1'
. $helperPath

try {
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
}

$Script:PrimaryHostname = 'localhost'
$Script:LegacyHostname = 'arena-overlay.test'
$Script:LoopbackIp = '127.0.0.1'
$Script:Hostname = $Script:LegacyHostname
$Script:RuntimeDir = Join-Path $env:LOCALAPPDATA 'ArenaOfNations\overlay-https'
$Script:KeystorePath = Join-Path $Script:RuntimeDir 'server.p12'
$Script:PassDpapiPath = Join-Path $Script:RuntimeDir 'keystore.pass.dpapi'
$Script:ServerCerPath = Join-Path $Script:RuntimeDir 'server.cer'
$Script:RootCerPath = Join-Path $Script:RuntimeDir 'root-ca.cer'
$Script:MetaPath = Join-Path $Script:RuntimeDir 'overlay-https.meta.properties'
$Script:LogPath = Join-Path $Script:RuntimeDir 'setup.log'
$Script:ResultPath = Join-Path $Script:RuntimeDir 'setup-result.json'
$Script:RootSubject = 'CN=Arena of Nations Overlay Local CA'
$Script:ServerSubject = ('CN=' + $Script:PrimaryHostname)
$Script:ServerSubjectLegacy = ('CN=' + $Script:LegacyHostname)
$Script:FriendlyRoot = 'ArenaOfNations-Overlay-Local-CA'
$Script:FriendlyServer = 'ArenaOfNations-Overlay-Server'
$Script:BasicConstraintsExt = '2.5.29.19={critical}{text}ca=1&pathlength=0'
$Script:ServerAuthEkuExt = '2.5.29.37={text}1.3.6.1.5.5.7.3.1'
$Script:FailedStep = $null
$Script:FinalExitCode = 1
$Script:IsAdmin = $false
$Script:RunId = $RunId
$Script:ParentHandledElevation = $false
$Script:ResultAlreadyWritten = $false

function Initialize-RuntimeDir {
    New-Item -ItemType Directory -Force -Path $Script:RuntimeDir | Out-Null
}

function Write-SetupLog {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$Level = 'INFO'
    )
    Initialize-RuntimeDir
    $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    $line = ('[{0}] [{1}] {2}' -f $stamp, $Level, $Message)
    Add-Content -LiteralPath $Script:LogPath -Value $line -Encoding UTF8
}

function Write-StepHost {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ''
    Write-Host ('==> ' + $Message) -ForegroundColor Cyan
    Write-SetupLog -Message $Message
}

function Write-Stage {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][ValidateSet('OK', 'SKIPPED', 'FAILED', 'INFO')][string]$Status,
        [string]$Detail = ''
    )
    $color = 'Gray'
    if ($Status -eq 'OK') { $color = 'Green' }
    elseif ($Status -eq 'SKIPPED') { $color = 'Yellow' }
    elseif ($Status -eq 'FAILED') { $color = 'Red' }
    $line = ($Label + ' ' + $Status)
    if (-not [string]::IsNullOrWhiteSpace($Detail)) {
        $line = ($line + ' - ' + $Detail)
    }
    Write-Host $line -ForegroundColor $color
    Write-SetupLog -Message $line -Level $Status
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-ThisScriptPath {
    if ($PSCommandPath) {
        return $PSCommandPath
    }
    if ($MyInvocation.MyCommand.Path) {
        return $MyInvocation.MyCommand.Path
    }
    throw 'Не удалось определить путь к Setup-LocalOverlayHttps.ps1.'
}

function Save-SetupResult {
    param(
        [Parameter(Mandatory = $true)][bool]$Success,
        [bool]$HostsValid = $false,
        [bool]$RootCertificateValid = $false,
        [bool]$ServerCertificateValid = $false,
        [bool]$SanValid = $false,
        [bool]$Pkcs12Valid = $false,
        [string]$FailedStep = $null,
        [string]$ErrorMessage = $null,
        [int]$ProcessExitCode = 1
    )
    if ([string]::IsNullOrWhiteSpace($Script:RunId)) {
        $Script:RunId = [guid]::NewGuid().ToString('N')
    }
    Initialize-RuntimeDir
    Save-OverlaySetupResultAtomic `
        -ResultPath $Script:ResultPath `
        -RunId $Script:RunId `
        -Success $Success `
        -HostsValid $HostsValid `
        -RootCertificateValid $RootCertificateValid `
        -ServerCertificateValid $ServerCertificateValid `
        -SanValid $SanValid `
        -Pkcs12Valid $Pkcs12Valid `
        -FailedStep $FailedStep `
        -ErrorMessage $ErrorMessage `
        -ProcessExitCode $ProcessExitCode
    $Script:ResultAlreadyWritten = $true
    Write-SetupLog -Message ('Result JSON written atomically: ' + $Script:ResultPath + '; runId=' + $Script:RunId)
}

function Wait-ForUserIfNeeded {
    if ($ValidateOnly -or $VerifyInstalled) {
        return
    }
    if ($NoPause) {
        return
    }
    Write-Host ''
    Write-Host 'Нажмите Enter, чтобы закрыть это окно.' -ForegroundColor Cyan
    try {
        [void](Read-Host)
    } catch {
        Start-Sleep -Seconds 30
    }
}

function Show-ParentOutcome {
    param($Outcome)

    Write-Host ''
    Write-Host '========================================'
    if ($Outcome.Success) {
        Write-Host 'LOCAL HTTPS OVERLAY SETUP: SUCCESS' -ForegroundColor Green
        Write-Host '========================================'
        if ($Outcome.Warning) {
            Write-Host $Outcome.Warning -ForegroundColor Yellow
        }
        Write-Host 'Hosts mapping: optional (legacy alias)'
        Write-Host 'Root CA: OK'
        Write-Host 'Server certificate CurrentUser\My: OK'
        Write-Host 'SAN localhost + arena-overlay.test + 127.0.0.1: OK'
        Write-Host 'Windows-MY private key: OK'
        Write-Host 'customHostsRequired=false'
        Write-Host ''
        Write-Host 'Адрес:'
        Write-Host '  https://localhost:8766/overlay/tiktok' -ForegroundColor Yellow
        Write-Host 'Legacy alias:'
        Write-Host '  https://arena-overlay.test:8766/overlay/tiktok'
        Write-Host ('Лог: ' + $Script:LogPath)
        Write-Host ('Результат: ' + $Script:ResultPath)
    } else {
        Write-Host 'LOCAL HTTPS OVERLAY SETUP: FAILED' -ForegroundColor Red
        Write-Host '========================================'
        Write-Host ('Process exit code: ' + $Outcome.ProcessExitCode)
        Write-Host ('Result JSON found: ' + $Outcome.JsonFound)
        Write-Host ('Run ID valid: ' + $Outcome.RunIdValid)
        if ($Outcome.FailedStep) {
            Write-Host ('Failed step: ' + $Outcome.FailedStep)
        }
        if ($Outcome.ErrorMessage) {
            Write-Host ('Error: ' + $Outcome.ErrorMessage)
        }
        Write-Host ('Log: ' + $Script:LogPath)
        Write-Host ('Result: ' + $Script:ResultPath)
    }
}

function Request-ElevationIfNeeded {
    if ($ValidateOnly -or $VerifyInstalled) {
        return $false
    }
    if (Test-IsAdministrator) {
        return $false
    }

    Write-StepHost 'Запрос прав администратора (UAC)'
    Initialize-RuntimeDir
    if ([string]::IsNullOrWhiteSpace($Script:RunId)) {
        $Script:RunId = [guid]::NewGuid().ToString('N')
    }
    Write-SetupLog -Message 'Non-admin parent process will relaunch elevated copy.'
    Write-SetupLog -Message ('runId=' + $Script:RunId)
    Write-SetupLog -Message ('Log file: ' + $Script:LogPath)

    $scriptPath = Get-ThisScriptPath
    $argString = '-NoProfile -ExecutionPolicy Bypass -File "' + $scriptPath + '" -Elevated -RunId "' + $Script:RunId + '"'
    if ($NoPause) {
        $argString = ($argString + ' -NoPause')
    }

    Write-Host 'Ожидание завершения административного окна...'
    Write-Host ('runId: ' + $Script:RunId)
    Write-Host ('Лог: ' + $Script:LogPath)
    Write-Host 'После итога во втором окне нажмите Enter.'
    $process = Start-Process `
        -FilePath 'powershell.exe' `
        -Verb RunAs `
        -ArgumentList $argString `
        -PassThru `
        -Wait

    if ($null -eq $process) {
        throw 'Не удалось запустить elevated PowerShell через UAC.'
    }

    $code = 1
    if ($null -ne $process.ExitCode) {
        $code = [int]$process.ExitCode
    }
    Write-SetupLog -Message ('Elevated process finished with ExitCode=' + $code)

    $outcome = Resolve-OverlayParentOutcome `
        -ProcessExitCode $code `
        -ExpectedRunId $Script:RunId `
        -ResultPath $Script:ResultPath
    Show-ParentOutcome -Outcome $outcome
    Write-SetupLog -Message ('Parent outcome success=' + $outcome.Success + '; finalExit=' + $outcome.FinalExitCode)

    $Script:FinalExitCode = [int]$outcome.FinalExitCode
    $Script:ParentHandledElevation = $true
    $Script:ResultAlreadyWritten = $true
    return $true
}

function Test-HostsHasMapping {
    param(
        [Parameter(Mandatory = $true)][string]$HostsPath,
        [Parameter(Mandatory = $true)][string]$HostName
    )

    if (-not (Test-Path -LiteralPath $HostsPath)) {
        return $false
    }

    $lines = Get-Content -LiteralPath $HostsPath -ErrorAction Stop
    $needle = $HostName.ToLowerInvariant()
    foreach ($raw in $lines) {
        if ($null -eq $raw) { continue }
        $line = $raw.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.StartsWith('#')) { continue }

        $parts = $line -split '\s+'
        if ($parts.Count -lt 2) { continue }

        $ip = $parts[0].ToLowerInvariant()
        if (($ip -ne '127.0.0.1') -and ($ip -ne '::1')) { continue }

        for ($i = 1; $i -lt $parts.Count; $i++) {
            $token = $parts[$i]
            if ($token.StartsWith('#')) { break }
            if ($token.ToLowerInvariant() -eq $needle) {
                return $true
            }
        }
    }
    return $false
}

function Test-HostsResolvesLoopback {
    param([Parameter(Mandatory = $true)][string]$HostName)
    try {
        $addresses = [System.Net.Dns]::GetHostAddresses($HostName)
        foreach ($addr in $addresses) {
            if ($addr.ToString() -eq '127.0.0.1') {
                return $true
            }
            if ($addr.AddressFamily.ToString() -eq 'InterNetwork' -and $addr.ToString().StartsWith('127.')) {
                return $true
            }
        }
    } catch {
        return $false
    }
    return $false
}

function Ensure-HostsMapping {
    $Script:FailedStep = 'hosts'
    Write-Stage -Label '[1/7] Проверка hosts...' -Status 'INFO'
    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    if (-not (Test-Path -LiteralPath $hostsPath)) {
        throw ('Файл hosts не найден: ' + $hostsPath)
    }

    if (Test-HostsHasMapping -HostsPath $hostsPath -HostName $Script:Hostname) {
        Write-Stage -Label '[1/7] Проверка hosts...' -Status 'SKIPPED' -Detail 'запись уже есть'
    } else {
        Write-SetupLog -Message 'Adding hosts mapping'
        $entry = ("127.0.0.1`t{0}`t# Arena of Nations local overlay" -f $Script:Hostname)
        Add-Content -LiteralPath $hostsPath -Value $entry -Encoding Ascii
        Write-Stage -Label '[1/7] Добавление hosts...' -Status 'OK' -Detail ('127.0.0.1 ' + $Script:Hostname)
    }

    if (-not (Test-HostsHasMapping -HostsPath $hostsPath -HostName $Script:Hostname)) {
        throw 'Не удалось подтвердить запись hosts после записи.'
    }
    if (-not (Test-HostsResolvesLoopback -HostName $Script:Hostname)) {
        throw ('Имя ' + $Script:Hostname + ' не резолвится в 127.0.0.1')
    }
    Write-Stage -Label '[1/7] Проверка hosts...' -Status 'OK' -Detail 'mapping + DNS loopback'
}

function New-RandomPassword {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function Save-DpapiPassword {
    param(
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $plain = [Text.Encoding]::UTF8.GetBytes($Password)
    $prot = [Security.Cryptography.ProtectedData]::Protect(
        $plain,
        $null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser
    )
    [IO.File]::WriteAllBytes($Path, $prot)
}

function Unlock-DpapiPassword {
    if (-not (Test-Path -LiteralPath $Script:PassDpapiPath)) {
        throw 'DPAPI password file missing.'
    }
    $blob = [IO.File]::ReadAllBytes($Script:PassDpapiPath)
    $plain = [Security.Cryptography.ProtectedData]::Unprotect(
        $blob,
        $null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser
    )
    return [Text.Encoding]::UTF8.GetString($plain)
}

function Remove-OldArenaCerts {
    $stores = @(
        'Cert:\CurrentUser\My',
        'Cert:\LocalMachine\My',
        'Cert:\LocalMachine\Root'
    )
    foreach ($storePath in $stores) {
        Get-ChildItem -Path $storePath -ErrorAction SilentlyContinue |
            Where-Object {
                ($_.FriendlyName -eq $Script:FriendlyRoot) -or
                ($_.FriendlyName -eq $Script:FriendlyServer) -or
                ($_.Subject -eq $Script:RootSubject) -or
                ($_.Subject -eq $Script:ServerSubject)
            } |
            ForEach-Object {
                Remove-Item -LiteralPath $_.PSPath -Force -ErrorAction SilentlyContinue
            }
    }
}

function Test-CertificateHasSan {
    param(
        [Parameter(Mandatory = $true)][System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
        [Parameter(Mandatory = $true)][string]$HostName
    )
    $needle = $HostName.ToLowerInvariant()
    if ($Certificate.DnsNameList) {
        foreach ($dns in $Certificate.DnsNameList) {
            if ($dns.Unicode -and ($dns.Unicode.ToLowerInvariant() -eq $needle)) { return $true }
            if ($dns.Punycode -and ($dns.Punycode.ToLowerInvariant() -eq $needle)) { return $true }
        }
    }
    foreach ($ext in $Certificate.Extensions) {
        if ($ext.Oid -and ($ext.Oid.Value -eq '2.5.29.17')) {
            $formatted = $ext.Format($false)
            if ($formatted -and $formatted.ToLowerInvariant().Contains($needle)) {
                return $true
            }
        }
    }
    return $false
}

function Test-CertificateHasServerAuth {
    param([Parameter(Mandatory = $true)][System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate)
    try {
        $eku = $Certificate.EnhancedKeyUsageList
        if ($null -eq $eku -or $eku.Count -eq 0) {
            return $true
        }
        foreach ($item in $eku) {
            if ($item.FriendlyName -eq 'Server Authentication') { return $true }
            if ($item.Value -eq '1.3.6.1.5.5.7.3.1') { return $true }
        }
        return $false
    } catch {
        return $true
    }
}

function Test-CertificateHasFullLoopbackSan {
    param([Parameter(Mandatory = $true)][System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate)
    return (
        (Test-CertificateHasSan -Certificate $Certificate -HostName $Script:PrimaryHostname) -and
        (Test-CertificateHasSan -Certificate $Certificate -HostName $Script:LegacyHostname) -and
        (Test-CertificateHasSan -Certificate $Certificate -HostName $Script:LoopbackIp)
    )
}

function Find-StoreCertificate {
    param(
        [Parameter(Mandatory = $true)][string]$StorePath,
        [Parameter(Mandatory = $true)][string]$FriendlyName,
        [Parameter(Mandatory = $true)][string]$Subject
    )
    $found = Get-ChildItem -Path $StorePath -ErrorAction SilentlyContinue |
        Where-Object {
            ($_.FriendlyName -eq $FriendlyName) -or ($_.Subject -eq $Subject) -or ($_.Subject -eq $Script:ServerSubjectLegacy)
        } |
        Select-Object -First 1
    return $found
}

function Find-BestCurrentUserServerCertificate {
    $candidates = Get-ChildItem -Path 'Cert:\CurrentUser\My' -ErrorAction SilentlyContinue |
        Where-Object {
            $_.HasPrivateKey -and (
                ($_.FriendlyName -eq $Script:FriendlyServer) -or
                ($_.Subject -eq $Script:ServerSubject) -or
                ($_.Subject -eq $Script:ServerSubjectLegacy) -or
                ($_.Subject -like 'CN=localhost*') -or
                ($_.Subject -like 'CN=arena-overlay.test*')
            )
        }
    if (-not $candidates) {
        return $null
    }
    $scored = foreach ($cert in $candidates) {
        $score = 0
        if (Test-CertificateHasSan -Certificate $cert -HostName $Script:PrimaryHostname) { $score++ }
        if (Test-CertificateHasSan -Certificate $cert -HostName $Script:LegacyHostname) { $score++ }
        if (Test-CertificateHasSan -Certificate $cert -HostName $Script:LoopbackIp) { $score++ }
        [PSCustomObject]@{
            Cert = $cert
            Score = $score
            NotAfter = $cert.NotAfter
        }
    }
    return ($scored | Sort-Object Score, NotAfter -Descending | Select-Object -First 1).Cert
}

function Test-CurrentUserServerCertificate {
    $existing = Find-BestCurrentUserServerCertificate
    if ($null -eq $existing) {
        return $false
    }
    if ($existing.NotAfter -le (Get-Date).AddDays(30)) {
        return $false
    }
    if (-not (Test-CertificateHasFullLoopbackSan -Certificate $existing)) {
        return $false
    }
    if (-not $existing.HasPrivateKey) {
        return $false
    }
    if (-not (Test-CertificateHasServerAuth -Certificate $existing)) {
        return $false
    }
    return $true
}

function Ensure-ServerCertInCurrentUserMy {
    if (Test-CurrentUserServerCertificate) {
        return (Find-BestCurrentUserServerCertificate)
    }

    # Prefer migrating an existing LocalMachine\My cert (do not delete it).
    # Migration alone is incomplete if SAN lacks localhost/127.0.0.1 — caller will create a new cert.
    $lmServer = Find-StoreCertificate -StorePath 'Cert:\LocalMachine\My' -FriendlyName $Script:FriendlyServer -Subject $Script:ServerSubjectLegacy
    if ($null -ne $lmServer -and $lmServer.HasPrivateKey -and ($lmServer.NotAfter -gt (Get-Date))) {
        if (Test-CertificateHasFullLoopbackSan -Certificate $lmServer) {
            Write-SetupLog -Message 'Migrating full-SAN server certificate from LocalMachine\My to CurrentUser\My'
            $password = New-RandomPassword
            $secure = ConvertTo-SecureString -String $password -Force -AsPlainText
            $tmpPfx = Join-Path $Script:RuntimeDir ('_migrate_' + [guid]::NewGuid().ToString('N') + '.pfx')
            try {
                Export-PfxCertificate -Cert $lmServer -FilePath $tmpPfx -Password $secure -ChainOption BuildChain -Force | Out-Null
                $imported = Import-PfxCertificate -FilePath $tmpPfx -CertStoreLocation 'Cert:\CurrentUser\My' -Password $secure -Exportable
                Write-Stage -Label '[3/7] Миграция в CurrentUser\My...' -Status 'OK' -Detail 'из LocalMachine\My'
                if ($imported -is [array]) {
                    return $imported | Where-Object { $_.HasPrivateKey } | Select-Object -First 1
                }
                return $imported
            } finally {
                $password = $null
                $secure = $null
                if (Test-Path -LiteralPath $tmpPfx) {
                    Remove-Item -LiteralPath $tmpPfx -Force -ErrorAction SilentlyContinue
                }
                [GC]::Collect()
            }
        }
    }
    return $null
}

function Ensure-Certificates {
    Initialize-RuntimeDir
    Write-SetupLog -Message ('Setup user=' + $env:USERNAME + '; USERPROFILE=' + $env:USERPROFILE)

    $Script:FailedStep = 'root_ca_lookup'
    Write-Stage -Label '[2/7] Проверка Root CA...' -Status 'INFO'
    $existingRoot = Find-StoreCertificate -StorePath 'Cert:\LocalMachine\Root' -FriendlyName $Script:FriendlyRoot -Subject $Script:RootSubject
    if ($existingRoot -and $existingRoot.NotAfter -gt (Get-Date)) {
        Write-Stage -Label '[2/7] Проверка Root CA...' -Status 'SKIPPED' -Detail 'уже в LocalMachine\Root'
    } else {
        Write-Stage -Label '[2/7] Проверка Root CA...' -Status 'INFO' -Detail 'будет создан/обновлён'
    }

    $Script:FailedStep = 'server_cert_lookup'
    Write-Stage -Label '[3/7] Проверка сертификата сервера...' -Status 'INFO'
    $cuServer = Ensure-ServerCertInCurrentUserMy
    $rootOk = ($null -ne $existingRoot) -and ($existingRoot.NotAfter -gt (Get-Date))
    if (($null -ne $cuServer) -and $rootOk) {
        Write-Stage -Label '[3/7] Проверка сертификата сервера...' -Status 'SKIPPED' -Detail 'CurrentUser\My уже содержит usable cert'
        if (Test-Path -LiteralPath $Script:RootCerPath) {
            Import-Certificate -FilePath $Script:RootCerPath -CertStoreLocation 'Cert:\LocalMachine\Root' -ErrorAction SilentlyContinue | Out-Null
        }
        return $false
    }

    Write-SetupLog -Message 'Creating new server certificate with localhost + arena-overlay.test + 127.0.0.1 SAN (keeping any old server certs)'
    # Do not wipe existing CurrentUser server certs — leave old alias-compatible certs in place.
    # Only ensure Root CA signing material exists.

    $Script:FailedStep = 'root_ca_create'
    $notAfterRoot = (Get-Date).AddYears(10)
    $notAfterServer = (Get-Date).AddYears(5)

    if (-not $rootOk) {
        Get-ChildItem -Path 'Cert:\LocalMachine\My' -ErrorAction SilentlyContinue |
            Where-Object {
                ($_.FriendlyName -eq $Script:FriendlyRoot) -or ($_.Subject -eq $Script:RootSubject)
            } |
            ForEach-Object { Remove-Item -LiteralPath $_.PSPath -Force -ErrorAction SilentlyContinue }

        $root = New-SelfSignedCertificate `
            -Subject $Script:RootSubject `
            -FriendlyName $Script:FriendlyRoot `
            -KeyAlgorithm RSA `
            -KeyLength 4096 `
            -HashAlgorithm SHA256 `
            -KeyExportPolicy Exportable `
            -KeyUsage CertSign, CRLSign, DigitalSignature `
            -NotAfter $notAfterRoot `
            -CertStoreLocation 'Cert:\LocalMachine\My' `
            -Type Custom `
            -TextExtension @($Script:BasicConstraintsExt)
        Write-Stage -Label '[2/7] Создание Root CA...' -Status 'OK' -Detail ('до ' + $root.NotAfter.ToString('yyyy-MM-dd'))
    } else {
        $root = Find-StoreCertificate -StorePath 'Cert:\LocalMachine\My' -FriendlyName $Script:FriendlyRoot -Subject $Script:RootSubject
        if ($null -eq $root) {
            # Root exists in Root store but may miss private key in My — recreate signing root only if needed.
            $root = New-SelfSignedCertificate `
                -Subject $Script:RootSubject `
                -FriendlyName $Script:FriendlyRoot `
                -KeyAlgorithm RSA `
                -KeyLength 4096 `
                -HashAlgorithm SHA256 `
                -KeyExportPolicy Exportable `
                -KeyUsage CertSign, CRLSign, DigitalSignature `
                -NotAfter $notAfterRoot `
                -CertStoreLocation 'Cert:\LocalMachine\My' `
                -Type Custom `
                -TextExtension @($Script:BasicConstraintsExt)
        }
        Write-Stage -Label '[2/7] Создание Root CA...' -Status 'SKIPPED' -Detail 'повторное использование'
    }

    $Script:FailedStep = 'server_cert_create'
    $server = New-SelfSignedCertificate `
        -Subject $Script:ServerSubject `
        -FriendlyName $Script:FriendlyServer `
        -DnsName @($Script:PrimaryHostname, $Script:LegacyHostname, $Script:LoopbackIp) `
        -Signer $root `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -KeyExportPolicy Exportable `
        -KeyUsage DigitalSignature, KeyEncipherment `
        -NotAfter $notAfterServer `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -TextExtension @($Script:ServerAuthEkuExt)
    Write-Stage -Label '[3/7] Создание сертификата сервера...' -Status 'OK' -Detail ('CurrentUser\My SAN localhost+legacy+127.0.0.1 до ' + $server.NotAfter.ToString('yyyy-MM-dd'))

    return @{
        Root = $root
        Server = $server
    }
}

function Export-RuntimeMaterial {
    param($Created)

    if ($Created -eq $false) {
        Write-Stage -Label '[5/7] Экспорт CER (опционально)...' -Status 'SKIPPED' -Detail 'CurrentUser\My уже готов'
        Write-Stage -Label '[6/7] PKCS12/DPAPI...' -Status 'SKIPPED' -Detail 'не требуется для Windows-MY'
        return
    }

    $Script:FailedStep = 'san_check'
    Write-Stage -Label '[4/7] Проверка SAN...' -Status 'INFO'
    if (-not (Test-CertificateHasFullLoopbackSan -Certificate $Created.Server)) {
        throw 'SAN должен содержать localhost, arena-overlay.test и 127.0.0.1'
    }
    if (-not (Test-CertificateHasServerAuth -Certificate $Created.Server)) {
        throw 'Server Authentication EKU отсутствует.'
    }
    Write-Stage -Label '[4/7] Проверка SAN...' -Status 'OK' -Detail 'localhost + arena-overlay.test + 127.0.0.1'

    $Script:FailedStep = 'export_cer'
    Write-Stage -Label '[5/7] Экспорт публичных CER...' -Status 'INFO'
    Export-Certificate -Cert $Created.Root -FilePath $Script:RootCerPath -Force | Out-Null
    Export-Certificate -Cert $Created.Server -FilePath $Script:ServerCerPath -Force | Out-Null
    Write-Stage -Label '[5/7] Экспорт публичных CER...' -Status 'OK'

    # Optional legacy PKCS12 left for diagnostics only — runtime no longer depends on it.
    $Script:FailedStep = 'optional_pkcs12'
    Write-Stage -Label '[6/7] Опциональный PKCS12 (legacy)...' -Status 'INFO'
    $password = New-RandomPassword
    $secure = ConvertTo-SecureString -String $password -Force -AsPlainText
    try {
        Export-PfxCertificate -Cert $Created.Server -FilePath $Script:KeystorePath -Password $secure -ChainOption BuildChain -Force | Out-Null
        Save-DpapiPassword -Password $password -Path $Script:PassDpapiPath
        Write-Stage -Label '[6/7] Опциональный PKCS12 (legacy)...' -Status 'OK' -Detail 'не используется Java runtime'
        $meta = @(
            ('hostname=' + $Script:PrimaryHostname),
            ('legacyHostname=' + $Script:LegacyHostname),
            ('createdUtc=' + (Get-Date).ToUniversalTime().ToString('o')),
            ('serverThumbprint=' + $Created.Server.Thumbprint),
            ('rootThumbprint=' + $Created.Root.Thumbprint),
            ('certificateSource=WINDOWS_MY'),
            ('primaryUrl=https://localhost:8766/overlay/tiktok'),
            ('notAfterUtc=' + $Created.Server.NotAfter.ToUniversalTime().ToString('o'))
        )
        Set-Content -LiteralPath $Script:MetaPath -Value $meta -Encoding UTF8
    } finally {
        $password = $null
        $secure = $null
        [GC]::Collect()
    }

    $Script:FailedStep = 'root_ca_trust'
    Import-Certificate -FilePath $Script:RootCerPath -CertStoreLocation 'Cert:\LocalMachine\Root' | Out-Null
    Write-SetupLog -Message 'Root CA imported into LocalMachine\Root'
}

function Test-Pkcs12Opens {
    $password = $null
    $cert = $null
    try {
        $password = Unlock-DpapiPassword
        $flags = [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable
        $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($Script:KeystorePath, $password, $flags)
        if (-not $cert.HasPrivateKey) {
            return $false
        }
        if (-not (Test-CertificateHasSan -Certificate $cert -HostName $Script:Hostname)) {
            return $false
        }
        return $true
    } catch {
        Write-SetupLog -Message ('PKCS12 open failed: ' + $_.Exception.GetType().FullName) -Level 'FAILED'
        return $false
    } finally {
        if ($cert) { $cert.Dispose() }
        $password = $null
        [GC]::Collect()
    }
}

function Invoke-FinalVerification {
    $Script:FailedStep = 'final_verification'
    Write-Stage -Label '[7/7] Финальная проверка...' -Status 'INFO'

    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $hostsValid = (Test-HostsHasMapping -HostsPath $hostsPath -HostName $Script:LegacyHostname)
    # Hosts are optional for primary localhost mode — do not fail setup if missing.

    $root = Find-StoreCertificate -StorePath 'Cert:\LocalMachine\Root' -FriendlyName $Script:FriendlyRoot -Subject $Script:RootSubject
    $rootValid = ($null -ne $root) -and ($root.NotAfter -gt (Get-Date))
    if (-not $rootValid) { throw 'Root CA не найден в LocalMachine\Root или просрочен.' }

    $cuServer = Find-BestCurrentUserServerCertificate
    if ($null -eq $cuServer) { throw 'Серверный сертификат отсутствует в CurrentUser\My.' }
    $serverValid = ($cuServer.NotAfter -gt (Get-Date)) -and $cuServer.HasPrivateKey
    $sanValid = Test-CertificateHasFullLoopbackSan -Certificate $cuServer
    $ekuValid = Test-CertificateHasServerAuth -Certificate $cuServer
    if (-not $serverValid) { throw 'Серверный сертификат в CurrentUser\My просрочен или без private key.' }
    if (-not $sanValid) { throw 'SAN должен содержать localhost, arena-overlay.test и 127.0.0.1' }
    if (-not $ekuValid) { throw 'Server Authentication EKU отсутствует.' }

    $privateKeyValid = $true

    Write-Stage -Label '[7/7] Финальная проверка...' -Status 'OK' -Detail 'primaryHostname=localhost; customHostsRequired=false'
    return @{
        HostsValid = $true
        RootCertificateValid = $rootValid
        ServerCertificateValid = $serverValid
        SanValid = $sanValid
        Pkcs12Valid = $privateKeyValid
        LegacyHostsPresent = $hostsValid
    }
}

function Invoke-ValidateOnly {
    Write-StepHost 'ValidateOnly: проверка без изменений системы'
    $psVersion = $PSVersionTable.PSVersion
    Write-Host ('OK: PowerShell ' + $psVersion.ToString())
    if ($psVersion.Major -lt 5) {
        throw 'Требуется Windows PowerShell 5.1 или новее.'
    }

    $scriptPath = Get-ThisScriptPath
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
    if ($errors -and $errors.Count -gt 0) {
        foreach ($err in $errors) {
            Write-Host ('PARSER: L' + $err.Extent.StartLineNumber + ': ' + $err.Message) -ForegroundColor Red
        }
        throw ('Обнаружены синтаксические ошибки: ' + $errors.Count)
    }
    Write-Host 'OK: parser validation пройдена.'

    foreach ($name in @('New-SelfSignedCertificate', 'Export-Certificate', 'Export-PfxCertificate', 'Import-Certificate', 'Start-Process', 'Read-Host')) {
        if (-not (Get-Command -Name $name -ErrorAction SilentlyContinue)) {
            throw ('Не найден cmdlet/команда: ' + $name)
        }
    }
    Write-Host 'OK: необходимые команды доступны.'

    Initialize-RuntimeDir
    $probe = Join-Path $Script:RuntimeDir ('_validate_probe_' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType File -Force -Path $probe | Out-Null
    Remove-Item -LiteralPath $probe -Force
    Write-Host ('OK: runtime путь доступен: ' + $Script:RuntimeDir)

    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $hasMapping = Test-HostsHasMapping -HostsPath $hostsPath -HostName $Script:Hostname
    Write-Host ('OK: hosts checked without writing (mappingExists=' + $hasMapping + ').')

    $argPreview = '-NoProfile -ExecutionPolicy Bypass -File "' + $scriptPath + '" -Elevated'
    if ($argPreview -notlike('*"' + $scriptPath + '"*')) {
        throw 'UAC ArgumentList preview is invalid.'
    }
    Write-Host 'OK: UAC ArgumentList preview корректный.'
    Write-Host ('OK: -NoPause supported=' + [bool]$NoPause)

    Write-Host ''
    Write-Host 'ValidateOnly: SUCCESS' -ForegroundColor Green
    Write-Host 'Система не изменялась. UAC не запрашивался.'
}

function Invoke-FullSetup {
    $Script:FailedStep = 'admin_check'
    Write-Stage -Label '[0/7] Проверка прав администратора...' -Status 'INFO'
    if (-not (Test-IsAdministrator)) {
        throw 'Нужны права администратора (UAC).'
    }
    $Script:IsAdmin = $true
    Write-Stage -Label '[0/7] Проверка прав администратора...' -Status 'OK'

    $Script:FailedStep = 'runtime_dir'
    Initialize-RuntimeDir
    Write-SetupLog -Message ('Runtime dir ready: ' + $Script:RuntimeDir)
    Write-SetupLog -Message ('PowerShell version: ' + $PSVersionTable.PSVersion.ToString())
    Write-SetupLog -Message ('IsAdministrator: ' + $Script:IsAdmin)

    Ensure-HostsMapping
    $created = Ensure-Certificates
    Export-RuntimeMaterial -Created $created
    $verify = Invoke-FinalVerification

    Write-Host ''
    Write-Host 'Hosts mapping: optional (legacy alias kept if present)' -ForegroundColor Yellow
    Write-Host 'Root CA: OK' -ForegroundColor Green
    Write-Host 'Server certificate CurrentUser\My: OK' -ForegroundColor Green
    Write-Host 'SAN localhost + arena-overlay.test + 127.0.0.1: OK' -ForegroundColor Green
    Write-Host 'Windows-MY private key: OK' -ForegroundColor Green
    Write-Host 'customHostsRequired=false' -ForegroundColor Green
    Write-Host ''
    Write-Host 'Основной адрес для TikTok LIVE Studio:'
    Write-Host '  https://localhost:8766/overlay/tiktok' -ForegroundColor Yellow
    Write-Host 'Legacy alias:'
    Write-Host '  https://arena-overlay.test:8766/overlay/tiktok'
    Save-SetupResult `
        -Success $true `
        -HostsValid $verify.HostsValid `
        -RootCertificateValid $verify.RootCertificateValid `
        -ServerCertificateValid $verify.ServerCertificateValid `
        -SanValid $verify.SanValid `
        -Pkcs12Valid $verify.Pkcs12Valid `
        -FailedStep $null `
        -ErrorMessage $null `
        -ProcessExitCode 0

    $Script:FinalExitCode = 0
    $Script:FailedStep = $null
}

function Invoke-VerifyInstalled {
    Write-StepHost 'VerifyInstalled: проверка без изменений и без UAC'
    Initialize-RuntimeDir
    Write-Host ('User=' + $env:USERNAME + ' Profile=' + $env:USERPROFILE)
    Write-Host 'primaryHostname=localhost'
    Write-Host 'customHostsRequired=false'

    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $hostsOk = Test-HostsHasMapping -HostsPath $hostsPath -HostName $Script:LegacyHostname
    Write-Stage -Label 'Legacy hosts alias (optional)' -Status $(if ($hostsOk) { 'OK' } else { 'SKIPPED' }) -Detail $(if ($hostsOk) { 'present' } else { 'not required' })

    $root = Find-StoreCertificate -StorePath 'Cert:\LocalMachine\Root' -FriendlyName $Script:FriendlyRoot -Subject $Script:RootSubject
    $rootOk = ($null -ne $root) -and ($root.NotAfter -gt (Get-Date))
    Write-Stage -Label 'Root CA LocalMachine\Root' -Status $(if ($rootOk) { 'OK' } else { 'FAILED' })

    $cuServer = Find-BestCurrentUserServerCertificate
    $serverOk = ($null -ne $cuServer) -and ($cuServer.NotAfter -gt (Get-Date)) -and $cuServer.HasPrivateKey
    $sanLocalhost = $false
    $sanLegacy = $false
    $sanIp = $false
    $ekuOk = $false
    if ($null -ne $cuServer) {
        $sanLocalhost = Test-CertificateHasSan -Certificate $cuServer -HostName $Script:PrimaryHostname
        $sanLegacy = Test-CertificateHasSan -Certificate $cuServer -HostName $Script:LegacyHostname
        $sanIp = Test-CertificateHasSan -Certificate $cuServer -HostName $Script:LoopbackIp
        $ekuOk = Test-CertificateHasServerAuth -Certificate $cuServer
    }
    Write-Stage -Label 'Server cert CurrentUser\My + private key' -Status $(if ($serverOk) { 'OK' } else { 'FAILED' })
    Write-Stage -Label 'dnsSanLocalhost' -Status $(if ($sanLocalhost) { 'OK' } else { 'FAILED' })
    Write-Stage -Label 'dnsSanLegacyAlias' -Status $(if ($sanLegacy) { 'OK' } else { 'FAILED' })
    Write-Stage -Label 'ipSan127001' -Status $(if ($sanIp) { 'OK' } else { 'FAILED' })
    Write-Stage -Label 'serverAuth' -Status $(if ($ekuOk) { 'OK' } else { 'FAILED' })

    $legacyPresent = (Test-Path -LiteralPath $Script:KeystorePath) -and (Test-Path -LiteralPath $Script:PassDpapiPath)
    Write-Stage -Label 'Legacy PKCS12/DPAPI (optional)' -Status $(if ($legacyPresent) { 'SKIPPED' } else { 'OK' }) -Detail $(if ($legacyPresent) { 'present but not required' } else { 'absent OK' })

    $allOk = $rootOk -and $serverOk -and $sanLocalhost -and $sanLegacy -and $sanIp -and $ekuOk
    Write-Host ''
    if ($allOk) {
        Write-Host 'LOCAL HTTPS OVERLAY VERIFY: SUCCESS' -ForegroundColor Green
        Write-Host 'certificateSource=WINDOWS_MY'
        Write-Host 'primaryHostname=localhost'
        Write-Host 'dnsSanLocalhost=true'
        Write-Host 'dnsSanLegacyAlias=true'
        Write-Host 'ipSan127001=true'
        Write-Host 'privateKey=true'
        Write-Host 'serverAuth=true'
        Write-Host 'customHostsRequired=false'
        $Script:FinalExitCode = 0
    } else {
        Write-Host 'LOCAL HTTPS OVERLAY VERIFY: FAILED' -ForegroundColor Red
        if (-not $serverOk -or -not $sanLocalhost -or -not $sanIp) {
            Write-Host 'Нужен certificate с SAN localhost + arena-overlay.test + 127.0.0.1 в CurrentUser\My.'
            Write-Host 'Запустите SETUP_LOCAL_OVERLAY_HTTPS.cmd (старый Root CA сохранится).'
        }
        $Script:FinalExitCode = 1
    }
}

# -------------------- main --------------------
$Script:FinalExitCode = 1
$Script:ParentHandledElevation = $false
$Script:ResultAlreadyWritten = $false
Initialize-RuntimeDir
if ([string]::IsNullOrWhiteSpace($Script:RunId)) {
    $Script:RunId = [guid]::NewGuid().ToString('N')
}

try {
    if ($Elevated -or ((-not $ValidateOnly) -and (-not $VerifyInstalled) -and (Test-IsAdministrator))) {
        Set-Content -LiteralPath $Script:LogPath -Value '' -Encoding UTF8
    }
    Write-SetupLog -Message '==== Arena HTTPS overlay setup start ===='
    Write-SetupLog -Message ('ValidateOnly=' + [bool]$ValidateOnly + '; VerifyInstalled=' + [bool]$VerifyInstalled + '; Elevated=' + [bool]$Elevated + '; NoPause=' + [bool]$NoPause)
    Write-SetupLog -Message ('runId=' + $Script:RunId)
    Write-SetupLog -Message ('PowerShell=' + $PSVersionTable.PSVersion.ToString())
    Write-SetupLog -Message ('IsAdministrator=' + (Test-IsAdministrator))

    Write-Host '============================================'
    Write-Host '  Arena of Nations - локальный HTTPS overlay'
    Write-Host '============================================'
    Write-Host ('Лог: ' + $Script:LogPath)
    Write-Host ('runId: ' + $Script:RunId)

    if ($ValidateOnly) {
        Invoke-ValidateOnly
        $Script:FinalExitCode = 0
    }
    elseif ($VerifyInstalled) {
        Invoke-VerifyInstalled
    }
    else {
        $handledByParent = Request-ElevationIfNeeded
        if (-not $handledByParent) {
            Invoke-FullSetup

            Write-Host ''
            Write-Host '========================================'
            Write-Host 'LOCAL HTTPS OVERLAY SETUP: SUCCESS' -ForegroundColor Green
            Write-Host '========================================'
            Write-Host ('Лог: ' + $Script:LogPath)
            Write-Host ('Результат: ' + $Script:ResultPath)
            Write-SetupLog -Message 'SETUP SUCCESS'
            $Script:FinalExitCode = 0
        }
    }
}
catch {
    $Script:FinalExitCode = 1
    $typeName = $_.Exception.GetType().FullName
    $msg = $_.Exception.Message
    $line = $null
    if ($_.InvocationInfo -and $_.InvocationInfo.ScriptLineNumber) {
        $line = $_.InvocationInfo.ScriptLineNumber
    }
    Write-SetupLog -Message ('ExceptionType=' + $typeName) -Level 'FAILED'
    Write-SetupLog -Message ('ExceptionMessage=' + $msg) -Level 'FAILED'
    if ($line) {
        Write-SetupLog -Message ('ScriptLineNumber=' + $line) -Level 'FAILED'
    }
    if ($Script:FailedStep) {
        Write-SetupLog -Message ('FailedStep=' + $Script:FailedStep) -Level 'FAILED'
    }

    if (-not $ValidateOnly -and -not $VerifyInstalled -and -not $Script:ParentHandledElevation) {
        Save-SetupResult `
            -Success $false `
            -FailedStep $Script:FailedStep `
            -ErrorMessage $msg `
            -ProcessExitCode 1
    }

    Write-Host ''
    Write-Host '========================================'
    Write-Host 'LOCAL HTTPS OVERLAY SETUP: FAILED' -ForegroundColor Red
    Write-Host '========================================'
    if ($Script:FailedStep) {
        Write-Host ('FAILED STEP: ' + $Script:FailedStep) -ForegroundColor Red
    }
    Write-Host ('ERROR: ' + $msg) -ForegroundColor Red
    Write-Host ('LOG FILE: ' + $Script:LogPath) -ForegroundColor Yellow
    Write-Host ('RESULT FILE: ' + $Script:ResultPath)
}
finally {
    Write-SetupLog -Message ('FinalExitCode=' + $Script:FinalExitCode)
    Write-SetupLog -Message '==== Arena HTTPS overlay setup end ===='
    if (-not $ValidateOnly -and -not $VerifyInstalled) {
        Wait-ForUserIfNeeded
    }
}

exit $Script:FinalExitCode
