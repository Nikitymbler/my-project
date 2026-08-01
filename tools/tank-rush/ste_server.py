# -*- coding: utf-8 -*-
"""Tank Rush LIVE — StreamToEarn bridge with SERVER-SIDE viewer binding.

Chat  -> bind viewerId to country (ru / ua / kz / ...)
Gift  -> add HP to that viewer's country (else not_bound)

Port 8080 (does not conflict with Arena Minecraft on 8765).
"""
from __future__ import annotations

import json
import mimetypes
import os
import sys
import threading
import time
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HOST = "127.0.0.1"
PORT = 8080
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
GAME_CANDIDATES = (
    "Tank_Rush_LIVE_VIEWER_BINDING.html",
    "tank-rush-tiktok-overlay.html",
    "index.html",
)
MAX_EVENTS = 5000
MAX_LOG = 80

COUNTRIES: dict[str, dict[str, str]] = {
    "russia": {"id": "russia", "code": "ru", "name": "Россия"},
    "ukraine": {"id": "ukraine", "code": "ua", "name": "Украина"},
    "belarus": {"id": "belarus", "code": "by", "name": "Беларусь"},
    "kazakhstan": {"id": "kazakhstan", "code": "kz", "name": "Казахстан"},
    "lithuania": {"id": "lithuania", "code": "lt", "name": "Литва"},
    "poland": {"id": "poland", "code": "pl", "name": "Польша"},
    "israel": {"id": "israel", "code": "il", "name": "Израиль"},
    "armenia": {"id": "armenia", "code": "am", "name": "Армения"},
    "uzbekistan": {"id": "uzbekistan", "code": "uz", "name": "Узбекистан"},
    "tajikistan": {"id": "tajikistan", "code": "tj", "name": "Таджикистан"},
    "georgia": {"id": "georgia", "code": "ge", "name": "Грузия"},
    "kyrgyzstan": {"id": "kyrgyzstan", "code": "kg", "name": "Кыргызстан"},
    "turkmenistan": {"id": "turkmenistan", "code": "tm", "name": "Туркменистан"},
    "moldova": {"id": "moldova", "code": "md", "name": "Молдова"},
    "azerbaijan": {"id": "azerbaijan", "code": "az", "name": "Азербайджан"},
    "latvia": {"id": "latvia", "code": "lv", "name": "Латвия"},
    "albania": {"id": "albania", "code": "al", "name": "Албания"},
    "bulgaria": {"id": "bulgaria", "code": "bg", "name": "Болгария"},
}

_EXTRA_ALIASES = {
    "белоруссия": "belarus",
    "киргизия": "kyrgyzstan",
    "молдавия": "moldova",
    "россия": "russia",
    "украина": "ukraine",
    "казахстан": "kazakhstan",
}

_lock = threading.Lock()
_events: list[dict[str, Any]] = []
_next_id = 1
_instance_id = str(uuid.uuid4())
_recent_external_ids: set[str] = set()
_recent_external_order: list[str] = []
_bindings: dict[str, dict[str, Any]] = {}  # viewerKey -> {countryId, viewerId, viewerName, at}
_scores: dict[str, int] = {cid: 0 for cid in COUNTRIES}
_log: list[str] = []
_hits = {"chat": 0, "gift_ok": 0, "gift_denied": 0}


def find_game_file() -> str | None:
    for name in GAME_CANDIDATES:
        path = os.path.join(BASE_DIR, name)
        if os.path.isfile(path):
            return path
    return None


def cors(handler: BaseHTTPRequestHandler) -> None:
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    handler.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
    handler.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
    handler.send_header("Pragma", "no-cache")
    handler.send_header("Expires", "0")


def send_json(handler: BaseHTTPRequestHandler, payload: Any, status: int = 200) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    cors(handler)
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def send_html(handler: BaseHTTPRequestHandler, html: str, status: int = 200) -> None:
    body = html.encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "text/html; charset=utf-8")
    cors(handler)
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def clean(value: Any, default: str = "") -> str:
    text = str(value if value is not None else "").strip()
    if not text:
        return default
    # Unreplaced StreamToEarn placeholders like {uniqueid}
    if text.startswith("{") and text.endswith("}") and len(text) <= 40:
        return default
    return text.replace("\r", " ").replace("\n", " ")[:1000]


def to_int(value: Any, default: int = 0, minimum: int = 0, maximum: int = 1_000_000_000) -> int:
    try:
        text = clean(value)
        if not text:
            return default
        number = int(float(text.replace(",", ".")))
        return max(minimum, min(maximum, number))
    except Exception:
        return default


def first(data: dict[str, Any], *keys: str, default: Any = "") -> Any:
    lower_map = {str(k).lower(): v for k, v in data.items()}
    for key in keys:
        value = data.get(key)
        if value is None:
            value = lower_map.get(key.lower())
        if isinstance(value, list):
            value = value[0] if value else None
        if value is not None and str(value).strip() != "":
            return value
    return default


def normalize_key(value: Any) -> str:
    text = str(value or "").strip().lower().replace("ё", "е")
    text = text.lstrip("/!#")
    out = []
    for ch in text:
        if ch.isalnum():
            out.append(ch)
    return "".join(out)


def build_alias_map() -> dict[str, str]:
    aliases: dict[str, str] = {}
    for country in COUNTRIES.values():
        for key in (country["id"], country["code"], country["name"]):
            aliases[normalize_key(key)] = country["id"]
    for key, country_id in _EXTRA_ALIASES.items():
        aliases[normalize_key(key)] = country_id
    return aliases


ALIASES = build_alias_map()


def resolve_country(message: Any) -> dict[str, str] | None:
    country_id = ALIASES.get(normalize_key(message))
    if not country_id:
        return None
    return COUNTRIES.get(country_id)


def viewer_key(viewer_id: str, viewer_name: str) -> str:
    return normalize_key(viewer_id or viewer_name)


def remember_external_id(external_id: str) -> bool:
    if not external_id:
        return False
    with _lock:
        if external_id in _recent_external_ids:
            return True
        _recent_external_ids.add(external_id)
        _recent_external_order.append(external_id)
        while len(_recent_external_order) > 20_000:
            old = _recent_external_order.pop(0)
            _recent_external_ids.discard(old)
    return False


def push_log(line: str) -> None:
    stamp = time.strftime("%H:%M:%S")
    with _lock:
        _log.append(f"[{stamp}] {line}")
        if len(_log) > MAX_LOG:
            del _log[: len(_log) - MAX_LOG]


def enqueue(event_type: str, payload: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    global _next_id
    external_id = clean(payload.get("eventId") or payload.get("externalId"))
    if external_id and remember_external_id(f"{event_type}:{external_id}"):
        return {"ok": True, "success": True, "duplicate": True, "eventId": external_id}, True

    with _lock:
        event = {
            "id": _next_id,
            "type": event_type,
            "receivedAt": time.time(),
            **payload,
        }
        _next_id += 1
        _events.append(event)
        if len(_events) > MAX_EVENTS:
            del _events[: len(_events) - MAX_EVENTS]
    return event, False


def parse_body(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    length = int(handler.headers.get("Content-Length") or 0)
    raw = handler.rfile.read(length) if length > 0 else b""
    if not raw:
        return {}
    text = raw.decode("utf-8", errors="replace").strip()
    if text.startswith("\ufeff"):
        text = text.lstrip("\ufeff")
    if not text:
        return {}
    try:
        parsed = json.loads(text)
        if isinstance(parsed, str):
            parsed = json.loads(parsed)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        form = urllib.parse.parse_qs(text, keep_blank_values=True)
        return {key: values[0] if values else "" for key, values in form.items()}


def merged_params(path: str, body: dict[str, Any] | None = None) -> tuple[str, dict[str, Any]]:
    parsed = urllib.parse.urlparse(path)
    query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    data: dict[str, Any] = {key: values[0] if values else "" for key, values in query.items()}
    if body:
        for key, value in body.items():
            if key not in data or data[key] == "":
                data[key] = value
    return parsed.path.rstrip("/") or "/", data


def build_viewer(data: dict[str, Any]) -> tuple[str, str]:
    viewer_id = clean(
        first(
            data,
            "viewerId",
            "viewerid",
            "uniqueId",
            "uniqueid",
            "unique_id",
            "userId",
            "userid",
            "username",
            "user",
        )
    )
    viewer_name = clean(
        first(data, "viewerName", "viewername", "nickname", "displayName", "username", "user"),
        "Зритель",
    )
    if not viewer_id:
        viewer_id = viewer_name
    return viewer_id[:200], viewer_name[:200]


def accept_chat(data: dict[str, Any]) -> tuple[dict[str, Any], int]:
    viewer_id, viewer_name = build_viewer(data)
    message = clean(first(data, "message", "comment", "text", "chat", "command", "msg"))
    event_id = clean(first(data, "eventId", "eventid", "externalId", "externalid", "id"))
    if not viewer_id:
        return {"ok": False, "success": False, "error": "missing viewerId"}, 200
    if not message:
        return {"ok": False, "success": False, "error": "missing message/comment"}, 200

    country = resolve_country(message)
    key = viewer_key(viewer_id, viewer_name)
    with _lock:
        _hits["chat"] += 1
        previous = (_bindings.get(key) or {}).get("countryId")
        if country:
            _bindings[key] = {
                "viewerId": viewer_id,
                "viewerName": viewer_name,
                "countryId": country["id"],
                "countryCode": country["code"],
                "countryName": country["name"],
                "at": time.time(),
            }

    event, duplicate = enqueue(
        "chat",
        {
            "viewerId": viewer_id,
            "viewerName": viewer_name,
            "message": message[:500],
            "eventId": event_id,
            "countryId": country["id"] if country else "",
            "bound": bool(country),
        },
    )
    if not country:
        push_log(f"CHAT miss {viewer_name}: {message}")
        print(f"[CHAT-MISS] {viewer_name} ({viewer_id}): {message}")
        return {
            "ok": False,
            "success": False,
            "error": "unknown_country",
            "message": message,
            "hint": "Пиши код страны: ru, ua, kz, by, ...",
            "event": event,
            "duplicate": duplicate,
        }, 200

    switched = bool(previous and previous != country["id"])
    push_log(f"CHAT {viewer_name} → {country['name']} ({country['code']})")
    print(f"[CHAT] {viewer_name} ({viewer_id}): {message} -> {country['id']}")
    return {
        "ok": True,
        "success": True,
        "bound": True,
        "switched": switched,
        "viewerId": viewer_id,
        "viewerName": viewer_name,
        "countryId": country["id"],
        "countryCode": country["code"],
        "countryName": country["name"],
        "duplicate": duplicate,
        "event": event,
    }, 200


def accept_gift(data: dict[str, Any]) -> tuple[dict[str, Any], int]:
    viewer_id, viewer_name = build_viewer(data)
    coins = to_int(first(data, "coins", "coin", "price", "points", "diamondCount", "diamonds"), 1, 1, 1_000_000)
    gift_count = to_int(first(data, "giftcount", "giftCount", "count", "repeat", "repeatCount"), 1, 1, 100_000)
    gift_name = clean(first(data, "giftName", "giftname", "name", "gift"), "Подарок")
    event_id = clean(first(data, "eventId", "eventid", "externalId", "externalid", "id"))
    # Optional direct country (legacy URL mode)
    direct = resolve_country(first(data, "country", "countryId", "countryCode", "team"))
    if not viewer_id:
        return {"ok": False, "success": False, "error": "missing viewerId"}, 200

    key = viewer_key(viewer_id, viewer_name)
    with _lock:
        binding = dict(_bindings.get(key) or {})
    country_id = (direct or {}).get("id") or binding.get("countryId") or ""
    country = COUNTRIES.get(country_id)
    total_hp = min(1_000_000_000, coins * gift_count)

    if not country:
        with _lock:
            _hits["gift_denied"] += 1
        event, duplicate = enqueue(
            "gift",
            {
                "viewerId": viewer_id,
                "viewerName": viewer_name,
                "giftName": gift_name[:300],
                "coins": coins,
                "giftcount": gift_count,
                "totalHp": total_hp,
                "eventId": event_id,
                "ok": False,
                "reason": "not_bound",
            },
        )
        push_log(f"GIFT DENIED {viewer_name}: сначала чат ru/ua/...")
        print(f"[GIFT-DENIED] {viewer_name} ({viewer_id}) not_bound")
        return {
            "ok": False,
            "success": False,
            "error": "not_bound",
            "reason": "not_bound",
            "viewerId": viewer_id,
            "hint": "Сначала зритель пишет в чат код страны (ru), потом дарит",
            "duplicate": duplicate,
            "event": event,
        }, 200

    with _lock:
        _scores[country["id"]] = int(_scores.get(country["id"], 0)) + total_hp
        new_score = _scores[country["id"]]
        _hits["gift_ok"] += 1

    event, duplicate = enqueue(
        "gift",
        {
            "viewerId": viewer_id,
            "viewerName": viewer_name,
            "giftName": gift_name[:300],
            "coins": coins,
            "giftcount": gift_count,
            "totalHp": total_hp,
            "eventId": event_id,
            "countryId": country["id"],
            "ok": True,
        },
    )
    push_log(f"GIFT {viewer_name} → {country['name']} +{total_hp} HP (всего {new_score})")
    print(f"[GIFT] {viewer_name} ({viewer_id}): {gift_name} | {coins}x{gift_count}={total_hp} -> {country['id']}")
    return {
        "ok": True,
        "success": True,
        "bound": True,
        "viewerId": viewer_id,
        "viewerName": viewer_name,
        "countryId": country["id"],
        "countryCode": country["code"],
        "countryName": country["name"],
        "coins": coins,
        "giftcount": gift_count,
        "delta": total_hp,
        "totalHp": total_hp,
        "score": new_score,
        "giftName": gift_name,
        "duplicate": duplicate,
        "event": event,
    }, 200


def status_payload() -> dict[str, Any]:
    with _lock:
        scores = [
            {
                "countryId": cid,
                "code": COUNTRIES[cid]["code"],
                "name": COUNTRIES[cid]["name"],
                "hp": int(_scores.get(cid, 0)),
            }
            for cid in COUNTRIES
            if int(_scores.get(cid, 0)) > 0
        ]
        scores.sort(key=lambda row: (-row["hp"], row["code"]))
        bindings = sorted(
            list(_bindings.values()),
            key=lambda row: -float(row.get("at") or 0),
        )[:50]
        return {
            "ok": True,
            "success": True,
            "service": "tank-rush-viewer-binding",
            "version": 2,
            "port": PORT,
            "instanceId": _instance_id,
            "gameFile": os.path.basename(find_game_file() or "") or None,
            "hits": dict(_hits),
            "bindingCount": len(_bindings),
            "scores": scores,
            "bindings": bindings,
            "log": list(_log[-40:]),
            "chatUrl": f"http://{HOST}:{PORT}/chat?viewerId={{uniqueid}}&viewerName={{nickname}}&message={{comment}}&eventId={{eventid}}",
            "giftUrl": f"http://{HOST}:{PORT}/gift?viewerId={{uniqueid}}&viewerName={{nickname}}&coins={{coins}}&giftcount={{giftcount}}&giftName={{giftname}}&eventId={{eventid}}",
        }


def status_page() -> str:
    data = status_payload()
    scores_html = "".join(
        f"<tr><td>{row['code'].upper()}</td><td>{row['name']}</td><td><b>{row['hp']}</b></td></tr>"
        for row in data["scores"]
    ) or "<tr><td colspan=3>Пока пусто — сделай chat → gift</td></tr>"
    binds_html = "".join(
        f"<tr><td>{row.get('viewerName','')}</td><td>{row.get('viewerId','')}</td>"
        f"<td>{row.get('countryCode','').upper()} {row.get('countryName','')}</td></tr>"
        for row in data["bindings"]
    ) or "<tr><td colspan=3>Нет привязок</td></tr>"
    log_html = "<br>".join(data["log"]) or "—"
    game = data["gameFile"] or "нет (статус всё равно работает)"
    return f"""<!doctype html>
<html lang="ru"><head>
<meta charset="utf-8"/>
<meta http-equiv="refresh" content="2"/>
<title>Tank Rush STE — статус</title>
<style>
body{{font-family:Segoe UI,Arial,sans-serif;background:#10160f;color:#e8f0e4;margin:24px}}
a{{color:#9bf28e}} code{{background:#1c261a;padding:2px 6px;border-radius:4px}}
table{{border-collapse:collapse;width:100%;margin:12px 0 24px}}
td,th{{border:1px solid #2c3a28;padding:8px;text-align:left}}
th{{background:#1a2418}}
.box{{background:#162015;border:1px solid #2c3a28;border-radius:10px;padding:14px;margin:12px 0}}
.ok{{color:#9bf28e}}.bad{{color:#ff8f7a}}
</style></head><body>
<h1>Tank Rush — привязка StreamToEarn</h1>
<p class="ok">Мост онлайн · порт {PORT} · service={data['service']} v{data['version']}</p>
<div class="box">
<b>В StreamToEarn (Method = GET):</b><br><br>
CHAT:<br><code>{data['chatUrl']}</code><br><br>
GIFT:<br><code>{data['giftUrl']}</code>
</div>
<div class="box">
Hits: chat={data['hits']['chat']} · gift_ok={data['hits']['gift_ok']} · gift_denied={data['hits']['gift_denied']}<br>
Игра HTML: {game} · <a href="/game">/game</a> · <a href="/health">/health</a> · <a href="/status">/status JSON</a>
</div>
<h2>Счёт HP</h2>
<table><tr><th>Код</th><th>Страна</th><th>HP</th></tr>{scores_html}</table>
<h2>Привязки зрителей</h2>
<table><tr><th>Ник</th><th>viewerId</th><th>Страна</th></tr>{binds_html}</table>
<h2>Лог</h2>
<div class="box">{log_html}</div>
<p>Проверка вручную:<br>
<a href="/chat?viewerId=test1&viewerName=Test&message=ru">1) chat ru</a> →
<a href="/gift?viewerId=test1&viewerName=Test&coins=5&giftcount=1&giftName=Rose">2) gift +5</a>
</p>
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        try:
            print(f"[HTTP] {self.address_string()} - {fmt % args}")
        except Exception:
            pass

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        cors(self)
        self.end_headers()

    def do_GET(self) -> None:
        try:
            self._handle(False)
        except Exception as exc:
            send_json(self, {"ok": False, "success": False, "error": str(exc)}, 500)

    def do_POST(self) -> None:
        try:
            self._handle(True)
        except Exception as exc:
            send_json(self, {"ok": False, "success": False, "error": str(exc)}, 500)

    def _handle(self, is_post: bool) -> None:
        global _events, _next_id, _bindings, _scores, _log, _hits
        body = parse_body(self) if is_post else {}
        path, data = merged_params(self.path, body)

        if path in ("/", "/index.html", "/panel", "/dashboard"):
            send_html(self, status_page())
            return

        if path in ("/game", "/play"):
            game = find_game_file()
            if not game:
                send_json(
                    self,
                    {
                        "ok": False,
                        "error": "game html missing",
                        "hint": "Положи Tank_Rush_LIVE_VIEWER_BINDING.html рядом со ste_server.py",
                    },
                    404,
                )
                return
            self.serve_game(game)
            return

        if path in ("/chat", "/message", "/comment", "/api/chat"):
            payload, status = accept_chat(data)
            send_json(self, payload, status)
            return

        if path in ("/gift", "/support", "/api/gift", "/webhook"):
            payload, status = accept_gift(data)
            send_json(self, payload, status)
            return

        if path in ("/events", "/poll", "/api/events"):
            since = to_int(first(data, "since", "after", "lastId"), 0, 0, 10**12)
            with _lock:
                last_id = _events[-1]["id"] if _events else 0
                effective_since = 0 if since > last_id else since
                snapshot = [dict(event) for event in _events if int(event.get("id", 0)) > effective_since]
            send_json(
                self,
                {
                    "ok": True,
                    "instanceId": _instance_id,
                    "events": snapshot,
                    "lastId": last_id,
                    "bindings": {k: v.get("countryId") for k, v in _bindings.items()},
                    "scores": dict(_scores),
                },
            )
            return

        if path in ("/status", "/state"):
            send_json(self, status_payload())
            return

        if path in ("/reset_events", "/reset"):
            with _lock:
                _events = []
                _next_id = 1
                _recent_external_ids.clear()
                _recent_external_order.clear()
                _bindings = {}
                _scores = {cid: 0 for cid in COUNTRIES}
                _log = []
                _hits = {"chat": 0, "gift_ok": 0, "gift_denied": 0}
            send_json(self, {"ok": True, "success": True, "instanceId": _instance_id})
            return

        if path == "/health":
            with _lock:
                last_id = _events[-1]["id"] if _events else 0
                count = len(_events)
            send_json(
                self,
                {
                    "ok": True,
                    "success": True,
                    "service": "tank-rush-viewer-binding",
                    "version": 2,
                    "port": PORT,
                    "instanceId": _instance_id,
                    "eventCount": count,
                    "lastId": last_id,
                    "bindingCount": len(_bindings),
                    "hits": dict(_hits),
                },
            )
            return

        rel = urllib.parse.unquote(path.lstrip("/"))
        file_path = os.path.normpath(os.path.join(BASE_DIR, rel))
        if file_path.startswith(BASE_DIR) and os.path.isfile(file_path):
            self.serve_file(file_path)
            return

        send_json(self, {"ok": False, "error": "not found", "path": path}, 404)

    def serve_game(self, game_path: str) -> None:
        with open(game_path, "rb") as fh:
            raw = fh.read()
        try:
            text = raw.decode("utf-8")
            text = text.replace("http://127.0.0.1:8765", f"http://{HOST}:{PORT}")
            text = text.replace("http://localhost:8765", f"http://{HOST}:{PORT}")
            payload = text.encode("utf-8")
        except Exception:
            payload = raw
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        cors(self)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def serve_file(self, file_path: str, content_type: str | None = None) -> None:
        mime = content_type or mimetypes.guess_type(file_path)[0] or "application/octet-stream"
        with open(file_path, "rb") as fh:
            payload = fh.read()
        self.send_response(200)
        self.send_header("Content-Type", mime)
        cors(self)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main() -> None:
    print("=" * 72)
    print("Tank Rush LIVE — StreamToEarn bridge v2 (server-side binding)")
    print(f"Статус:  http://{HOST}:{PORT}/")
    print(f"Health:  http://{HOST}:{PORT}/health")
    print("CHAT GET:")
    print(f"  http://{HOST}:{PORT}/chat?viewerId={{uniqueid}}&viewerName={{nickname}}&message={{comment}}&eventId={{eventid}}")
    print("GIFT GET:")
    print(f"  http://{HOST}:{PORT}/gift?viewerId={{uniqueid}}&viewerName={{nickname}}&coins={{coins}}&giftcount={{giftcount}}&giftName={{giftname}}&eventId={{eventid}}")
    print("Формула: HP = coins x giftcount")
    print("=" * 72)
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nОстановлено.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
