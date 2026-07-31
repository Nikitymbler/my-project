Arena of Nations — локальный HTTPS overlay
==========================================

Основной режим: TikTok LIVE Studio + захват окна + chroma key

URL окна:

  https://localhost:8766/overlay/tiktok?background=chroma

Цвет удаления (chroma key):

  #FF00FF

ОБЫЧНЫЙ ЗАПУСК
--------------
1. Запустить Minecraft через START_ARENA.cmd.
2. Открыть мир.
3. Запустить OPEN_OVERLAY_WINDOW.cmd
   (чистое окно Edge/Chrome 1080×1920 без вкладок).
4. В TikTok LIVE Studio добавить «Захват окна».
5. Выбрать окно Arena of Nations — TikTok Overlay.
6. Включить удаление цвета / chroma key.
7. Выбрать цвет: #FF00FF
8. Установить минимальную силу удаления, чтобы края текста оставались резкими.
9. Разместить overlay поверх захвата Minecraft.

Важно:
- внутри Minecraft Arena HUD не отображается;
- информация матча только в browser overlay;
- Cursor не нужен;
- URL вручную вводить не нужно;
- OBS не требуется.

ОДИН РАЗ (HTTPS)
----------------
1. SETUP_LOCAL_OVERLAY_HTTPS.cmd (UAC).
2. Дождаться SUCCESS.

ДИАГНОСТИКА
-----------
- OPEN_OVERLAY.cmd — preview с подписью CAPTURE MODE: CHROMA #FF00FF
- OPEN_OVERLAY_WINDOW.cmd — окно для захвата
- VERIFY_LOCAL_OVERLAY_HTTPS.cmd
- В игре: /arena_overlay_status

Порты
-----
8766 — HTTPS overlay
8765 — StreamToEarn gift/chat

Технический transparent mode (не основной):

  https://localhost:8766/overlay/tiktok?background=transparent
