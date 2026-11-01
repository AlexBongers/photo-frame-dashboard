#!/usr/bin/env python3
"""A small stand-in for Home Assistant, to test the panel without a real token.

  python3 panel/tools/mock_ha.py            # listens on 0.0.0.0:8124, token "testtoken"

It speaks just enough of the API: the WebSocket (auth, get_states, subscribe_events, ping), the
calendar and service endpoints. Toggling a light or setting a temperature changes the state and
pushes a state_changed event, like the real thing. The P1 power changes every two seconds.
"""
import base64
import hashlib
import json
import random
import struct
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TOKEN = "testtoken"
PORT = 8124
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

STATES = {}
CLIENTS = []
LOCK = threading.Lock()


def sensor(entity, value, unit, cls="temperature"):
    STATES[entity] = {"state": str(value), "attributes": {"unit_of_measurement": unit, "device_class": cls}}


for e, v in [("sensor.lexlauhome_buitentemperatuur", 8.6), ("sensor.eethoek_temp_sonos_temperatuur", 19.9),
             ("sensor.verwarming_temperatuur", 20.6), ("sensor.lucas_vindstyrka_temperatuur", 19.0),
             ("sensor.lana_vindstyrka_temperatuur", 19.0), ("sensor.zolder_temp_temperatuur", 18.0),
             ("sensor.hue_motion_sensor_garage_temperatuur", 13.3)]:
    sensor(e, v, "°C")
for e, v in [("sensor.p1_meter_vermogen", 353), ("sensor.p1_meter_vermogen_fase_1", 142),
             ("sensor.gang_p1_meter_vermogen_fase_2", 43), ("sensor.gang_p1_meter_vermogen_fase_3", 164),
             ("sensor.inverter_lexlau_vermogen", 0), ("sensor.plug_wasmachine_vermogen", 0),
             ("sensor.plexpluggang_vermogen", 15)]:
    sensor(e, v, "W", "power")
for e, v in [("sensor.inverter_lexlau_energy_today", 10.6), ("sensor.zolder_inverter_lexlauomvormer_energy_this_week", 35.9),
             ("sensor.inverter_lexlau_energy_this_month", 292), ("sensor.zolder_inverter_lexlauomvormer_energy_this_year", 3883.6)]:
    sensor(e, v, "kWh", "energy")

import os
for i in range(int(os.environ.get("MOCK_EXTRA", "0"))):
    STATES["sensor.filler_%d" % i] = {"state": str(i), "attributes": {"friendly_name": "Filler sensor %d" % i,
        "unit_of_measurement": "W", "device_class": "power", "state_class": "measurement", "icon": "mdi:flash",
        "supported_features": 0, "options": ["a", "b", "c"], "latitude": 52.1, "longitude": 4.3}}

TODO = [{"uid": "u1", "summary": "Melk", "status": "needs_action", "description": "2x halfvol"},
        {"uid": "u2", "summary": "Brood", "status": "needs_action"},
        {"uid": "u3", "summary": "Appels", "status": "needs_action", "description": "Elstar"},
        {"uid": "u4", "summary": "Pasta", "status": "needs_action"},
        {"uid": "u5", "summary": "Tomatenpuree", "status": "needs_action"},
        {"uid": "u6", "summary": "Koffie", "status": "completed"},
        {"uid": "u7", "summary": "Yoghurt", "status": "completed"},
        {"uid": "u8", "summary": "Kaas", "status": "completed"}]
STATES["todo.bring"] = {"state": "5", "attributes": {"friendly_name": "Bring boodschappen", "supported_features": 15}}

STATES["weather.buienradar"] = {"state": "cloudy", "attributes": {"temperature": 8.6, "humidity": 80}}

LIGHTS = {"light.woonkamer": 0, "light.keuken": 183, "light.eethoek": 125, "light.slaapkamer": 0, "light.zolder": 0,
          "light.badkamer": 0, "light.radiator_badkamer_2": 0, "light.buitenlampen": 143, "light.garage": 77,
          "light.kapschuur": 0, "light.3d_printers": 0}
for e, b in LIGHTS.items():
    STATES[e] = {"state": "on" if b else "off", "attributes": {"brightness": b or None, "supported_color_modes": ["brightness"]}}


def climate(entity, state, cur, target, modes, step=1):
    STATES[entity] = {"state": state, "attributes": {"current_temperature": cur, "temperature": target, "hvac_modes": modes,
                                                      "target_temp_step": step, "min_temp": 17, "max_temp": 30}}


climate("climate.midea_ac_144036023290591", "off", 19.7, 22, ["fan_only", "dry", "cool", "heat", "auto", "off"])
climate("climate.midea_ac_21990232567739", "cool", 19.5, 21, ["fan_only", "dry", "cool", "heat", "auto", "off"])
STATES["climate.midea_ac_18691697784083"] = {"state": "unavailable", "attributes": {}}
climate("climate.verwarming", "off", 20.7, 5, ["off", "auto", "heat"], 0.5)


def now_iso(dt):
    offset = time.strftime("%z", time.localtime(dt.timestamp()))
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + offset[:3] + ":" + offset[3:]


def calendar_events(entity):
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    out = []

    def timed(day, h1, m1, h2, m2, title):
        s = today + timedelta(days=day, hours=h1, minutes=m1)
        e = today + timedelta(days=day, hours=h2, minutes=m2)
        out.append({"summary": title, "start": {"dateTime": now_iso(s)}, "end": {"dateTime": now_iso(e)}})

    def allday(day, title):
        d = (today + timedelta(days=day)).strftime("%Y-%m-%d")
        d2 = (today + timedelta(days=day + 1)).strftime("%Y-%m-%d")
        out.append({"summary": title, "start": {"date": d}, "end": {"date": d2}})

    if entity == "calendar.family":
        timed(0, 15, 0, 21, 0, "Lau viert verjaardag")
        timed(2, 15, 30, 16, 30, "Lana Lucas tandarts")
        timed(3, 15, 0, 16, 0, "Lucas zwemles")
    elif entity == "calendar.alex_prive":
        timed(2, 17, 30, 18, 30, "Alex kapper")
        timed(5, 10, 0, 11, 30, "Boodschappen doen")
    elif entity == "calendar.alex_werk":
        timed(1, 9, 30, 10, 30, "Overleg team")
        timed(3, 13, 0, 14, 0, "Klantgesprek")
    elif entity == "calendar.feestdagen_in_nederland":
        allday(4, "Sinterklaasavond")
    elif entity == "calendar.verjaardagen":
        allday(1, "Verjaardag Opa")
    return out


def push_event(entity):
    msg = json.dumps({"id": 2, "type": "event", "event": {"event_type": "state_changed", "data": {
        "entity_id": entity, "new_state": {"entity_id": entity, **STATES[entity]}}}})
    with LOCK:
        for c in list(CLIENTS):
            try:
                c.send(msg)
            except Exception:
                CLIENTS.remove(c)


class WsConn:
    def __init__(self, wfile):
        self.wfile = wfile
        self.lock = threading.Lock()

    def send(self, text):
        data = text.encode()
        head = bytearray([0x81])
        if len(data) < 126:
            head.append(len(data))
        elif len(data) < 65536:
            head += bytes([126]) + struct.pack(">H", len(data))
        else:
            head += bytes([127]) + struct.pack(">Q", len(data))
        with self.lock:
            self.wfile.write(bytes(head) + data)
            self.wfile.flush()


def read_frame(rfile):
    h = rfile.read(2)
    if len(h) < 2:
        return None, None
    op = h[0] & 0x0F
    ln = h[1] & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", rfile.read(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", rfile.read(8))[0]
    mask = rfile.read(4) if h[1] & 0x80 else b"\0\0\0\0"
    data = bytearray(rfile.read(ln))
    for i in range(len(data)):
        data[i] ^= mask[i % 4]
    return op, bytes(data)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(self.address_string(), fmt % args)

    def authed(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/v1/search":  # stand-in for the Spotify Web API search
            q = parse_qs(url.query).get("q", [""])[0]
            if not self.headers.get("Authorization", "").startswith("Bearer mocktoken"):
                return self.json({"error": {"status": 401}}, 401)
            return self.json({
                "tracks": {"items": [{"name": "%s (track %d)" % (q.title(), i), "uri": "spotify:track:MOCK%d" % i,
                                      "artists": [{"name": "Artist %d" % i}], "album": {"name": "Album %d" % i}} for i in range(1, 7)]},
                "albums": {"items": [{"name": "%s Greatest Hits" % q.title(), "uri": "spotify:album:MOCKA1", "artists": [{"name": "Various"}]}]},
                "playlists": {"items": [None, {"name": "This is %s" % q.title(), "uri": "spotify:playlist:MOCKP1", "owner": {"display_name": "Spotify"}}]},
            })
        if url.path == "/api/websocket" and self.headers.get("Upgrade", "").lower() == "websocket":
            return self.websocket()
        if not self.authed():
            return self.json({"message": "unauthorized"}, 401)
        if url.path.startswith("/api/calendars/"):
            return self.json(calendar_events(url.path.rsplit("/", 1)[1]))
        if url.path == "/api/states":
            return self.json([{"entity_id": k, **v} for k, v in STATES.items()])
        self.json({"message": "not found"}, 404)

    def do_POST(self):
        url = urlparse(self.path)
        length = int(self.headers.get("Content-Length", 0))
        if url.path == "/api/token":  # stand-in for accounts.spotify.com
            self.rfile.read(length)
            if not self.headers.get("Authorization", "").startswith("Basic "):
                return self.json({"error": "invalid_client"}, 400)
            return self.json({"access_token": "mocktoken1", "token_type": "Bearer", "expires_in": 3600})
        data = json.loads(self.rfile.read(length) or b"{}")
        if not self.authed():
            return self.json({"message": "unauthorized"}, 401)
        parts = url.path.split("/")
        if len(parts) == 5 and parts[1] == "api" and parts[2] == "services":
            domain, service = parts[3], parts[4]
            entity = data.get("entity_id")
            if (domain, service) == ("weather", "get_forecasts"):
                days = [("cloudy", 10, 4), ("cloudy", 13, 6), ("rainy", 14, 8), ("partlycloudy", 12, 7), ("sunny", 10, 5)]
                fc = []
                for i, (cond, hi, lo) in enumerate(days):
                    d = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=i)
                    fc.append({"datetime": now_iso(d), "condition": cond, "temperature": hi, "templow": lo})
                return self.json({"changed_states": [], "service_response": {entity: {"forecast": fc}}})
            changed = []
            if domain == "todo":
                if service == "get_items":
                    want = data.get("status")
                    items = [i for i in TODO if not want or i["status"] in want]
                    return self.json({"changed_states": [], "service_response": {entity: {"items": items}}})
                if service == "add_item":
                    TODO.insert(0, {"uid": "n%d" % len(TODO), "summary": data["item"], "status": "needs_action"})
                elif service == "update_item":
                    for i in TODO:
                        if i["uid"] == data["item"] or i["summary"] == data["item"]:
                            i["status"] = data.get("status", i["status"])
                elif service == "remove_item":
                    TODO[:] = [i for i in TODO if i["uid"] != data["item"] and i["summary"] != data["item"]]
                STATES["todo.bring"]["state"] = str(sum(1 for i in TODO if i["status"] == "needs_action"))
                changed.append("todo.bring")
            if domain == "light" and entity in STATES:
                st = STATES[entity]
                if service == "toggle":
                    on = st["state"] != "on"
                    st["state"] = "on" if on else "off"
                    st["attributes"]["brightness"] = 200 if on else None
                elif service == "turn_on":
                    st["state"] = "on"
                    st["attributes"]["brightness"] = int(data.get("brightness_pct", 100) * 255 / 100)
                changed.append(entity)
            if domain == "climate" and entity in STATES:
                st = STATES[entity]
                if service == "set_hvac_mode":
                    st["state"] = data["hvac_mode"]
                elif service == "set_temperature":
                    st["attributes"]["temperature"] = data["temperature"]
                changed.append(entity)
            for e in changed:
                push_event(e)
            return self.json([])
        self.json({"message": "not found"}, 404)

    def websocket(self):
        key = self.headers["Sec-WebSocket-Key"]
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        self.wfile.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        self.wfile.flush()
        conn = WsConn(self.wfile)
        conn.send(json.dumps({"type": "auth_required", "ha_version": "mock"}))
        self.close_connection = True
        try:
            while True:
                op, data = read_frame(self.rfile)
                if op is None or op == 8:
                    break
                if op != 1:
                    continue
                m = json.loads(data)
                t = m.get("type")
                if t == "auth":
                    if m.get("access_token") == TOKEN:
                        conn.send(json.dumps({"type": "auth_ok", "ha_version": "mock"}))
                    else:
                        conn.send(json.dumps({"type": "auth_invalid", "message": "Invalid access token"}))
                elif t == "get_states":
                    conn.send(json.dumps({"id": m["id"], "type": "result", "success": True,
                                          "result": [{"entity_id": k, **v} for k, v in STATES.items()]}))
                elif t == "subscribe_events":
                    with LOCK:
                        CLIENTS.append(conn)
                    conn.send(json.dumps({"id": m["id"], "type": "result", "success": True, "result": None}))
                elif t == "ping":
                    conn.send(json.dumps({"id": m["id"], "type": "pong"}))
        except Exception as e:
            print("ws ended:", e)
        finally:
            with LOCK:
                if conn in CLIENTS:
                    CLIENTS.remove(conn)


def ticker():
    while True:
        time.sleep(2)
        v = max(120, int(STATES["sensor.p1_meter_vermogen"]["state"]) + random.randint(-40, 40))
        STATES["sensor.p1_meter_vermogen"]["state"] = str(v)
        push_event("sensor.p1_meter_vermogen")


if __name__ == "__main__":
    threading.Thread(target=ticker, daemon=True).start()
    print("mock Home Assistant on :%d, token %r" % (PORT, TOKEN))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
