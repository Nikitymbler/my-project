#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$Base = 'https://raw.githubusercontent.com/Nikitymbler/my-project/main'
$Targets = @(
  "$env:USERPROFILE\Desktop\my-project",
  "$env:USERPROFILE\Desktop\ArenaOfNations"
) | Where-Object { Test-Path (Join-Path $_ 'gradlew.bat') }

if (-not $Targets) {
  Write-Host 'FAIL: не найден my-project или ArenaOfNations на Desktop' -ForegroundColor Red
  exit 1
}

function Get-File($url, $path) {
  $dir = Split-Path $path -Parent
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Write-Host "  GET $(Split-Path $path -Leaf)"
  Invoke-WebRequest -Uri $url -OutFile $path -UseBasicParsing
}

$Mcmeta = @'
{
  "texture": {
    "blur": false,
    "clamp": true
  }
}
'@

foreach ($Root in $Targets) {
  Write-Host "`n=== $Root ===" -ForegroundColor Cyan
  Get-File "$Base/src/client/java/com/nikita/arenaofnations/client/ArenaFighterOverheadRenderer.java" `
    "$Root\src\client\java\com\nikita\arenaofnations\client\ArenaFighterOverheadRenderer.java"
  Get-File "$Base/src/client/java/com/nikita/arenaofnations/client/ArenaFighterFlagVisuals.java" `
    "$Root\src\client\java\com\nikita\arenaofnations\client\ArenaFighterFlagVisuals.java"
  Get-File "$Base/src/main/resources/assets/arena_of_nations/overlay/flags/us.svg" `
    "$Root\src\main\resources\assets\arena_of_nations\overlay\flags\us.svg"
  Get-File "$Base/src/main/resources/assets/arena_of_nations/textures/gui/flags/us.png" `
    "$Root\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png"
  Get-File "$Base/src/main/resources/assets/arena_of_nations/textures/gui/flags_hd/us.png" `
    "$Root\src\main\resources\assets\arena_of_nations\textures\gui\flags_hd\us.png"
  Get-File "$Base/src/main/resources/assets/arena_of_nations/overlay/tiktok/flags/us.png" `
    "$Root\src\main\resources\assets\arena_of_nations\overlay\tiktok\flags\us.png"

  $Mcmeta | Set-Content -Encoding UTF8 "$Root\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta"
  $Mcmeta | Set-Content -Encoding UTF8 "$Root\src\main\resources\assets\arena_of_nations\textures\gui\flags_hd\us.png.mcmeta"

  # Verify download contains two-sided fighter path and stars svg
  $ovr = Get-Content "$Root\src\client\java\com\nikita\arenaofnations\client\ArenaFighterOverheadRenderer.java" -Raw
  if ($ovr -notmatch 'blitFlagQuadBack') { throw "FAIL: FighterOverhead без blitFlagQuadBack в $Root" }
  $vis = Get-Content "$Root\src\client\java\com\nikita\arenaofnations\client\ArenaFighterFlagVisuals.java" -Raw
  if ($vis -notmatch 'flags_hd') { throw "FAIL: FighterFlagVisuals без flags_hd в $Root" }
  $svg = Get-Content "$Root\src\main\resources\assets\arena_of_nations\overlay\flags\us.svg" -Raw
  if ($svg -notmatch '<polygon') { throw "FAIL: us.svg без polygon-звёзд в $Root" }

  Write-Host 'Building...'
  Push-Location $Root
  try {
    & .\gradlew.bat build --quiet
    if ($LASTEXITCODE -ne 0) { throw "BUILD FAIL $Root code=$LASTEXITCODE" }
  } finally {
    Pop-Location
  }
  Write-Host "BUILD OK: $Root" -ForegroundColor Green
}

Write-Host ''
Write-Host 'ГОТОВО. Полностью закрой Minecraft (не только мир).' -ForegroundColor Yellow
Write-Host 'Потом START_ARENA.cmd из той папки, где играешь, зайди в мир, F3+T.' -ForegroundColor Yellow
Write-Host 'У бойца синий угол слева + звёзды у США.' -ForegroundColor Yellow
