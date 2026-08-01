# One-paste: install Tank Rush StreamToEarn binding bridge on port 8080
# Usage: irm https://raw.githubusercontent.com/Nikitymbler/my-project/main/tools/Install-TankRushS2E.ps1 | iex
$ErrorActionPreference = 'Stop'
$target = Join-Path $env:USERPROFILE 'Desktop\lplp'
$rawBase = 'https://raw.githubusercontent.com/Nikitymbler/my-project/cursor/tank-rush-s2e-8080-ce83/tools/tank-rush'
New-Item -ItemType Directory -Force -Path $target | Out-Null

$files = @(
    'ste_server.py',
    'START_TANK_RUSH_S2E.cmd',
    'STREAMTOEARN_LINKS.txt'
)
foreach ($name in $files) {
    $url = "$rawBase/$name"
    $out = Join-Path $target $name
    Write-Host "Downloading $name ..."
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
}

# Stop old python bridges on common ports if possible (best-effort)
Get-NetTCPConnection -LocalPort 8080,8765 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object {
        try {
            $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
            if ($p -and ($p.ProcessName -match 'python|py')) {
                Write-Host "Stopping old $($p.ProcessName) PID $($p.Id) on port $($_.LocalPort)"
                Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }

Start-Sleep -Seconds 1
Start-Process -FilePath (Join-Path $target 'START_TANK_RUSH_S2E.cmd') -WorkingDirectory $target
Write-Host ''
Write-Host 'OK. Use these StreamToEarn GET URLs:'
Write-Host 'CHAT: http://127.0.0.1:8080/chat?viewerId={uniqueid}&viewerName={nickname}&message={comment}&eventId={eventid}'
Write-Host 'GIFT: http://127.0.0.1:8080/gift?viewerId={uniqueid}&viewerName={nickname}&coins={coins}&giftcount={giftcount}&giftName={giftname}&eventId={eventid}'
Write-Host 'Health check: http://127.0.0.1:8080/health  (must show tank-rush-viewer-binding)'
