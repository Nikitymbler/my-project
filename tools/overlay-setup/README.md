# TikTok / OBS Overlay — локальный HTTPS

Постоянный адрес:

```text
https://localhost:8766/overlay/tiktok
```

Legacy alias:

```text
https://arena-overlay.test:8766/overlay/tiktok
```

Один раз: корневой `SETUP_LOCAL_OVERLAY_HTTPS.cmd` (UAC).  
Обычный запуск: `START_ARENA.cmd`.  
Проверка: `OPEN_OVERLAY.cmd`.  
Инструкция: `OVERLAY_README_RU.txt`.

## Порты

| Порт | Назначение |
|------|------------|
| **8766** | HTTPS overlay only |
| **8765** | StreamToEarn gift/chat (HTTP localhost) |

Cloudflare / Firebase / домен **не нужны**. Hosts для основного URL **не нужны**.
