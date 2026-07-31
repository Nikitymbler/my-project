# StreamToEarn — настройка интеграции с Arena of Nations

Arena of Nations принимает события зрителей через **локальный HTTP-мост** на `127.0.0.1`.  
Поток:

```text
StreamToEarn → HTTP POST → ArenaStreamToEarnCommands → ArenaViewerEventManager → серверный тик → ArenaMatchManager
```

Токены, пароли, ключи API и личный TikTok username в этот файл **не** записывать.

Команды `arena_s2e_*` остаются как **ручная диагностика / fallback**, но **не** как основной способ интеграции.

---

## Важно: ограничение HTTP Request в StreamToEarn

В текущем интерфейсе StreamToEarn для HTTP Request доступны только:

- URL;
- Method;
- POST Body.

**Полей для custom HTTP-заголовков нет**, поэтому заголовок `X-Arena-Token` из StreamToEarn отправить нельзя.

Поэтому для StreamToEarn используйте **совместимые endpoints** с токеном в теле запроса (`body-auth`).

Endpoints с заголовком `X-Arena-Token` (`/arena/chat`, `/arena/gift`) остаются для PowerShell и других клиентов, которые умеют headers.

---

## Настройка мода

В `config/arena_of_nations.properties` (для `runClient` — `run/config/arena_of_nations.properties`):

```properties
s2e_http_enabled=true
s2e_http_port=8765
s2e_http_token=CHANGE_TO_A_LONG_RANDOM_SECRET
```

Правила:

- используйте **длинный случайный** токен;
- **не публикуйте** токен в чатах, скриншотах и репозитории;
- сервер слушает только **`127.0.0.1`** — доступен с текущего компьютера;
- в singleplayer / integrated server мост работает **только пока открыт мир** Minecraft;
- изменение HTTP-настроек требует **перезапуска мира**;
- при `s2e_http_enabled=true` и пустом токене мост **не** стартует.

### Генерация токена в Windows PowerShell

Совместимо с Windows PowerShell **5.1** и PowerShell 7+:

```powershell
-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })
```

Альтернатива через .NET RNG:

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
($bytes | ForEach-Object { '{0:x2}' -f $_ }) -join ''
```

Подставьте результат в `s2e_http_token` (не коммитьте его).

---

## Health

```http
GET http://127.0.0.1:8765/arena/health
```

Ожидается HTTP **200**:

```json
{"ok":true,"service":"arena-of-nations-s2e"}
```

Токен не нужен.

```powershell
Invoke-WebRequest http://127.0.0.1:8765/arena/health -UseBasicParsing |
  Select-Object StatusCode, Content
```

---

## Для StreamToEarn: body-auth endpoints (рекомендуется)

StreamToEarn показывает ошибку **"Invalid JSON format"**, если POST Body не JSON.  
Custom headers в UI нет → токен передаётся **внутри JSON**.

Подтверждённые переменные интерфейса StreamToEarn:

| Переменная | Смысл |
|---|---|
| `{uniqueid}` | стабильный ID TikTok-пользователя → **viewerId** |
| `{nickname}` | отображаемое имя (не использовать как viewerId) |
| `{comment}` | текст комментария |
| `{coins}` | монеты подарка |
| `{giftname}` | название подарка |
| `{giftcount}` | количество подарков |

Используйте **`{uniqueid}`**, а не `{nickname}`, как `viewerId`.

### Chat (JSON — основной формат для StreamToEarn)

URL:

```text
http://127.0.0.1:8765/arena/streamtoearn/chat
```

Method: `POST`  
Content-Type: `application/json` (если UI позволяет; тело всё равно JSON)

Body:

```json
{
  "token": "<TOKEN>",
  "viewerId": "{uniqueid}",
  "message": "{comment}"
}
```

Пример после подстановки:

```json
{
  "token": "SECRET",
  "viewerId": "123456789",
  "message": "!ru"
}
```

Команды страны: `!<id>` для любого из 20 кодов (`!ru`, `!ua`, `!kz`, … `!us`).

### Gift (JSON — основной формат для StreamToEarn)

URL:

```text
http://127.0.0.1:8765/arena/streamtoearn/gift
```

Body:

```json
{
  "token": "<TOKEN>",
  "viewerId": "{uniqueid}",
  "coins": "{coins}"
}
```

`coins` может быть числом или строкой с целым числом.

**Шаблон напарника** `"coins": {coins}` (без кавычек) сам по себе **невалидный JSON**. Мост перед разбором сам закавычивает такие плейсхолдеры и снимает UTF-8 BOM. Для Play без эфира незаменённый `{coins}` / другой `{placeholder}` принимается как `1`. Лучше сразу писать `"coins": "{coins}"`.

Если снова **400**: в Minecraft `/arena_s2e_status` смотрите `HTTP hits gift=` (дошёл ли POST) и `ingress reject reason=`. После правки мода нужен **полный выход из мира и заход снова**.

Опционально `eventId` (только если появится настоящий уникальный id события):

```json
{
  "token": "<TOKEN>",
  "viewerId": "{uniqueid}",
  "coins": "{coins}",
  "eventId": "{gift_event_id}"
}
```

**Важно про eventId:**

- в текущем UI StreamToEarn **нет подтверждённой** переменной уникального gift eventId;
- **не придумывайте** eventId из `{nickname}`, random или текущего времени;
- без настоящего eventId дедупликация повторной отправки одного и того же подарка **невозможна**.

### Plain-text body-auth (диагностика / PowerShell)

Те же URL принимают и старый plain-text формат (если первый непробельный символ не `{`):

```text
<TOKEN>|||{uniqueid}|||{comment}
<TOKEN>|||{uniqueid}|||{coins}
```

Для StreamToEarn используйте **JSON**.

Лимиты body-auth:

- полное тело ≤ 1260 байт UTF-8;
- токен ≤ 256 байт;
- внутренний payload (после сборки `viewerId|||...`) ≤ 1000 байт.

---

## Для PowerShell и клиентов с headers: header-auth

```http
POST http://127.0.0.1:8765/arena/chat
X-Arena-Token: <TOKEN>
Content-Type: text/plain; charset=utf-8

viewerId|||message
```

```http
POST http://127.0.0.1:8765/arena/gift
X-Arena-Token: <TOKEN>
Content-Type: text/plain; charset=utf-8

viewerId|||coins
```

или:

```text
viewerId|||coins|||eventId
```

Максимум тела header-auth: **1000 байт**.

Эти endpoints **не** являются основным путём для текущего UI StreamToEarn.

---

## HTTP-коды

| Код | Значение |
|---:|---|
| 200 | health OK |
| 202 | событие принято в очередь |
| 400 | payload отклонён / нет разделителя `|||` / `malformed_json` / `missing_field` |
| 401 | нет или неверный токен |
| 405 | неверный HTTP-метод (`Allow` указывает допустимый) |
| 413 | превышен лимит размера |
| 500 | внутренняя ошибка (`reason=internal_error`) |

---

## Диагностика

| Команда | Что смотреть |
|---|---|
| `/arena_viewer_status` | очередь, gifts, duplicates, выбор стран |
| `/arena_s2e_status` | счётчики; `HTTP running` / `port` / `token configured` (**без токена**) |
| `/arena_status` | состояние матча |

| Симптом | Вероятная причина |
|---|---|
| 400 | нет `|||`, неверный формат payload, coins не число |
| 401 | неверный токен в body (S2E) или в `X-Arena-Token` (header-auth) |
| 405 | неверный HTTP-метод |
| 413 | тело / payload слишком большой |
| ошибка соединения | мир закрыт / мост выключен |
| порт занят | смените `s2e_http_port` и перезайдите в мир |
| пустой токен | задайте `s2e_http_token` |
| duplicate | повтор того же `eventId` |

Негативные HTTP-тесты (нужен открытый мир):

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\tools\test-s2e-http-bridge.ps1
```

---

## Fallback: командный мост

```text
/arena_s2e_chat viewer123|||!ru
/arena_s2e_gift viewer123|||50|||manual_1
```

Только для ручной диагностики.

---

## Безопасность

- Bind только `127.0.0.1` — не менять на `0.0.0.0`.
- Не передавать токен в URL или query string.
- Для StreamToEarn токен идёт **в теле** (ограничение UI); для других клиентов — в `X-Arena-Token`.
- Не логировать токен, payload, viewerId, комментарии.
- Не коммитить реальные токены.
- Использовать `{uniqueid}`, не `{nickname}`, как viewerId.
- Не выдумывать fake eventId.
