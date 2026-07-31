# Local HTTPS overlay setup (Windows)

One-time: run `SETUP_LOCAL_OVERLAY_HTTPS.cmd` from the project root (UAC).

Dry-run (no UAC, no hosts/cert changes):

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\overlay-https\Setup-LocalOverlayHttps.ps1" -ValidateOnly
```

Creates / updates after full setup:

- optional hosts: `127.0.0.1 arena-overlay.test` (legacy alias only)
- local Root CA trusted in `LocalMachine\Root` (reused if valid)
- server cert in `Cert:\CurrentUser\My` with SAN:
  - DNS `localhost`
  - DNS `arena-overlay.test`
  - IP `127.0.0.1`
- runtime uses Windows-MY / SunMSCAPI (PKCS12 optional)

Permanent LIVE Studio URL:

```text
https://localhost:8766/overlay/tiktok
```

`Setup-LocalOverlayHttps.ps1` must stay **UTF-8 with BOM** for Windows PowerShell 5.1.
