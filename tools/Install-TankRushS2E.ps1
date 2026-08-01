# One-paste reinstall Tank Rush StreamToEarn bridge (server-side binding, port 8080)
# irm https://raw.githubusercontent.com/Nikitymbler/my-project/cursor/tank-rush-s2e-8080-ce83/tools/Install-TankRushS2E.ps1 | iex
$ErrorActionPreference = 'Stop'
$target = Join-Path $env:USERPROFILE 'Desktop\lplp'
$branch = 'cursor/tank-rush-s2e-8080-ce83'
$rawBase = "https://raw.githubusercontent.com/Nikitymbler/my-project/$branch/tools/tank-rush"
New-Item -ItemType Directory -Force -Path $target | Out-Null

Write-Host 'Stopping old Python listeners on 8080/8765 ...'
Get-NetTCPConnection -LocalPort 8080,8765 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object {
        try {
            $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
            if ($p -and ($p.ProcessName -match 'python|py')) {
                Write-Host ("  kill {0} PID {1} port {2}" -f $p.ProcessName, $p.Id, $_.LocalPort)
                Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
Get-Process -Name python,python3,py -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path -like '*Python*' } |
    ForEach-Object {
        try {
            $cmd = (Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f $_.Id) -ErrorAction SilentlyContinue).CommandLine
            if ($cmd -and ($cmd -match 'ste_server')) {
                Write-Host ("  kill ste_server PID {0}" -f $_.Id)
                Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }

Start-Sleep -Seconds 1

foreach ($name in @('ste_server.py', 'START_TANK_RUSH_S2E.cmd', 'STREAMTOEARN_LINKS.txt', 'Tank_Rush_LIVE_VIEWER_BINDING.html')) {
    $url = "$rawBase/$name"
    $out = Join-Path $target $name
    Write-Host "Downloading $name ..."
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
}

Write-Host 'Starting bridge ...'
Start-Process -FilePath (Join-Path $target 'START_TANK_RUSH_S2E.cmd') -WorkingDirectory $target
Start-Sleep -Seconds 2

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 5
    Write-Host ("HEALTH OK: service={0} version={1}" -f $health.service, $health.version)
} catch {
    Write-Host 'WARNING: health check failed — смотри окно Python'
}

Write-Host ''
Write-Host '========================================'
Write-Host 'StreamToEarn — Method GET, body пустой'
Write-Host '========================================'
Write-Host 'CHAT:'
Write-Host 'http://127.0.0.1:8080/chat?viewerId={uniqueid}&viewerName={nickname}&message={comment}&eventId={eventid}'
Write-Host ''
Write-Host 'GIFT:'
Write-Host 'http://127.0.0.1:8080/gift?viewerId={uniqueid}&viewerName={nickname}&coins={coins}&giftcount={giftcount}&giftName={giftname}&eventId={eventid}'
Write-Host ''
Write-Host 'ИГРА:    http://127.0.0.1:8080/game   << ОТКРЫВАЙ ЭТО, не FIXED_v3 с диска'
Write-Host 'Статус:  http://127.0.0.1:8080/'
Write-Host 'Health:  http://127.0.0.1:8080/health  (version должен быть 2)'
Write-Host 'Тест:    http://127.0.0.1:8080/chat?viewerId=test1&viewerName=Test&message=ru'
Write-Host '         http://127.0.0.1:8080/gift?viewerId=test1&viewerName=Test&coins=5&giftcount=1&giftName=Rose'
