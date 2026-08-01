# -*- coding: utf-8 -*-
"""Tank Rush LIVE — local StreamToEarn bridge.

The bridge receives two kinds of GET/POST events from StreamToEarn:
  /chat  -> viewer selects or changes country with ru / ua / kz / ...
  /gift  -> gift adds HP to the country saved for that viewer

The game polls /events and keeps viewer bindings in browser localStorage.
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
# 8080: не конфликтует с Arena Minecraft S2E на 8765
PORT = 8080
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
GAME_CANDIDATES = (
    "Tank_Rush_LIVE_VIEWER_BINDING.html",
    "tank-rush-tiktok-overlay.html",
    "index.html",
)
GAME_FILE = next(
    (os.path.join(BASE_DIR, name) for name in GAME_CANDIDATES if os.path.isfile(os.path.join(BASE_DIR, name))),
    os.path.join(BASE_DIR, GAME_CANDIDATES[0]),
)
MAX_EVENTS = 5000

_lock = threading.Lock()
_events: list[dict[str, Any]] = []
_next_id = 1
_instance_id = str(uuid.uuid4())
_recent_external_ids: set[str] = set()
_recent_external_order: list[str] = []


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


def clean(value: Any, default: str = "") -> str:
    text = str(value if value is not None else "").strip()
    if not text or (text.startswith("{") and text.endswith("}")):
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
    for key in keys:
        value = data.get(key)
        if isinstance(value, list):
            value = value[0] if value else None
        if value is not None and str(value).strip() != "":
            return value
    return default


def remember_external_id(external_id: str) -> bool:
    """Return True if duplicate."""
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


def enqueue(event_type: str, payload: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    global _next_id
    external_id = clean(payload.get("eventId") or payload.get("externalId"))
    if external_id and remember_external_id(f"{event_type}:{external_id}"):
        return {"ok": True, "duplicate": True, "eventId": external_id}, True

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
    if not text:
        return {}
    try:
        parsed = json.loads(text)
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
    return parsed.path, data


def build_viewer(data: dict[str, Any]) -> tuple[str, str]:
    viewer_id = clean(first(data, "viewerId", "viewerid", "uniqueId", "uniqueid", "userId", "userid", "username", "user"))
    viewer_name = clean(first(data, "viewerName", "viewername", "nickname", "displayName", "username", "user"), "Зритель")
    if not viewer_id:
        viewer_id = viewer_name
    return viewer_id[:200], viewer_name[:200]


def accept_chat(data: dict[str, Any]) -> tuple[dict[str, Any], int]:
    viewer_id, viewer_name = build_viewer(data)
    message = clean(first(data, "message", "comment", "text", "chat", "command"))
    event_id = clean(first(data, "eventId", "eventid", "externalId", "externalid", "id"))
    if not viewer_id:
        return {"ok": False, "error": "missing viewerId/viewerName"}, 400
    if not message:
        return {"ok": False, "error": "missing message/comment"}, 400
    event, duplicate = enqueue("chat", {
        "viewerId": viewer_id,
        "viewerName": viewer_name,
        "message": message[:500],
        "eventId": event_id,
    })
    if not duplicate:
        print(f"[CHAT] {viewer_name} ({viewer_id}): {message}")
    return {"ok": True, "duplicate": duplicate, "event": event}, 200


def accept_gift(data: dict[str, Any]) -> tuple[dict[str, Any], int]:
    viewer_id, viewer_name = build_viewer(data)
    coins = to_int(first(data, "coins", "coin", "price", "points", "diamondCount"), 1, 1, 1_000_000)
    gift_count = to_int(first(data, "giftcount", "giftCount", "count", "repeat", "repeatCount"), 1, 1, 100_000)
    gift_name = clean(first(data, "giftName", "giftname", "name", "gift"), "Подарок")
    event_id = clean(first(data, "eventId", "eventid", "externalId", "externalid", "id"))
    if not viewer_id:
        return {"ok": False, "error": "missing viewerId/viewerName"}, 400
    total_hp = min(1_000_000_000, coins * gift_count)
    event, duplicate = enqueue("gift", {
        "viewerId": viewer_id,
        "viewerName": viewer_name,
        "giftName": gift_name[:300],
        "coins": coins,
        "giftcount": gift_count,
        "totalHp": total_hp,
        "eventId": event_id,
    })
    if not duplicate:
        print(f"[GIFT] {viewer_name} ({viewer_id}): {gift_name} | {coins} x {gift_count} = {total_hp} HP")
    return {"ok": True, "duplicate": duplicate, "event": event}, 200


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
            send_json(self, {"ok": False, "error": str(exc)}, 500)

    def do_POST(self) -> None:
        try:
            self._handle(True)
        except Exception as exc:
            send_json(self, {"ok": False, "error": str(exc)}, 500)

    def _handle(self, is_post: bool) -> None:
        global _events, _next_id
        body = parse_body(self) if is_post else {}
        path, data = merged_params(self.path, body)

        if path in ("/", "/index.html", "/game"):
            self.serve_game()
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
                # If bridge restarted, browser may send an old high cursor.
                effective_since = 0 if since > last_id else since
                snapshot = [dict(event) for event in _events if int(event.get("id", 0)) > effective_since]
            send_json(self, {
                "ok": True,
                "instanceId": _instance_id,
                "events": snapshot,
                "lastId": last_id,
            })
            return

        if path in ("/reset_events", "/reset"):
            with _lock:
                _events = []
                _next_id = 1
                _recent_external_ids.clear()
                _recent_external_order.clear()
            send_json(self, {"ok": True, "instanceId": _instance_id, "events": [], "lastId": 0})
            return

        if path == "/health":
            with _lock:
                last_id = _events[-1]["id"] if _events else 0
                count = len(_events)
            send_json(self, {
                "ok": True,
                "service": "tank-rush-viewer-binding",
                "instanceId": _instance_id,
                "eventCount": count,
                "lastId": last_id,
            })
            return

        rel = urllib.parse.unquote(path.lstrip("/"))
        file_path = os.path.normpath(os.path.join(BASE_DIR, rel))
        if file_path.startswith(BASE_DIR) and os.path.isfile(file_path):
            self.serve_file(file_path)
            return

        send_json(self, {"ok": False, "error": "not found"}, 404)

    def serve_game(self) -> None:
        if not os.path.isfile(GAME_FILE):
            send_json(self, {"ok": False, "error": f"missing {os.path.basename(GAME_FILE)}"}, 500)
            return
        self.serve_file(GAME_FILE, "text/html; charset=utf-8")

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
    print("Tank Rush LIVE — StreamToEarn bridge: сообщения + подарки")
    print(f"Игра:    http://{HOST}:{PORT}/")
    print("Chat:    /chat?viewerId={uniqueid}&viewerName={nickname}&message={comment}&eventId={eventid}")
    print("Gift:    /gift?viewerId={uniqueid}&viewerName={nickname}&coins={coins}&giftcount={giftcount}&giftName={giftname}&eventId={eventid}")
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
