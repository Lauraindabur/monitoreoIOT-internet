#!/usr/bin/env python3
"""
Servidor HTTP para la interfaz web del sistema distribuido de monitoreo IoT.

La capa web mantiene:
 - autenticacion contra un servicio externo
 - consulta de sensores activos y mediciones recientes
 - un stream de alertas del servidor IoT
 - una interfaz modular con HTML, CSS y JS separados
"""

from __future__ import annotations

import argparse
import html
import http.client
import json
import mimetypes
import os
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from string import Template
from urllib.parse import parse_qs, urlparse

try:
    from .dashboard_service import AlertFeed, DashboardService, IotClient
except ImportError:
    from dashboard_service import AlertFeed, DashboardService, IotClient


BASE_DIR = Path(__file__).resolve().parent
TEMPLATES_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"


def _timestamp() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _log(level: str, message: str) -> None:
    print(f"[{_timestamp()}] [{level}] [WEB] {message}", flush=True)


_sessions: dict[str, dict] = {}
_sessions_lock = threading.Lock()


def create_session(username: str, role: str) -> str:
    session_id = str(uuid.uuid4())
    with _sessions_lock:
        _sessions[session_id] = {
            "username": username,
            "role": role,
            "created_at": _timestamp(),
        }
    _log("INFO", f"Sesion creada para '{username}' (rol: {role})")
    return session_id


def get_session(session_id: str) -> dict | None:
    with _sessions_lock:
        return _sessions.get(session_id)


def delete_session(session_id: str) -> None:
    with _sessions_lock:
        removed = _sessions.pop(session_id, None)
    if removed:
        _log("INFO", f"Sesion eliminada para '{removed['username']}'")


def authenticate_user(username: str, password: str, auth_host: str, auth_port: int) -> dict | None:
    try:
        payload = json.dumps({"username": username, "password": password}).encode("utf-8")
        _log("INFO", f"Consultando autenticacion externa para '{username}' en {auth_host}:{auth_port}")

        conn = http.client.HTTPConnection(auth_host, auth_port, timeout=5)
        conn.request(
            "POST",
            "/auth",
            body=payload,
            headers={
                "Content-Type": "application/json",
                "Content-Length": str(len(payload)),
            },
        )
        response = conn.getresponse()
        body = response.read().decode("utf-8")
        conn.close()

        data = json.loads(body)
        if response.status == 200 and data.get("status") == "ok":
            _log("INFO", f"Autenticacion exitosa para '{username}'")
            return data

        _log("WARN", f"Autenticacion rechazada para '{username}'")
        return None
    except Exception as exc:
        _log("WARN", f"Error consultando el servicio de autenticacion: {exc}")
        return None


def _load_template(name: str) -> Template:
    template_path = TEMPLATES_DIR / name
    return Template(template_path.read_text(encoding="utf-8"))


def _render_flash(kind: str, message: str) -> str:
    if not message:
        return ""
    return f'<div class="flash {kind}">{html.escape(message)}</div>'


def _build_nav(session: dict | None) -> str:
    if not session:
        return '<a href="/login">Iniciar sesion</a>'

    return (
        f'<span class="nav-user"><strong>{html.escape(session["username"])}</strong>'
        f'<span class="nav-subtle">{html.escape(session["role"])}</span></span>'
        '<a href="/logout">Cerrar sesion</a>'
    )


def _render_page(
    *,
    title: str,
    body_class: str,
    page_class: str,
    session: dict | None,
    content_html: str,
    include_app_js: bool,
) -> str:
    base_template = _load_template("base.html")
    script_tag = '<script src="/static/app.js" defer></script>' if include_app_js else ""
    return base_template.safe_substitute(
        title=html.escape(title),
        body_class=body_class,
        page_class=page_class,
        nav_html=_build_nav(session),
        content_html=content_html,
        head_extras="",
        script_tag=script_tag,
    )


def render_dashboard_page(session: dict) -> str:
    content_html = _load_template("dashboard.html").safe_substitute()
    return _render_page(
        title="Dashboard IoT",
        body_class="dashboard-page",
        page_class="dashboard-page",
        session=session,
        content_html=content_html,
        include_app_js=True,
    )


def render_login_page(error_msg: str = "", info_msg: str = "") -> str:
    content_html = _load_template("login.html").safe_substitute(
        error_html=_render_flash("error", error_msg),
        info_html=_render_flash("info", info_msg),
    )
    return _render_page(
        title="Inicio de sesion",
        body_class="login-page",
        page_class="login-page",
        session=None,
        content_html=content_html,
        include_app_js=False,
    )


def render_message_page(title: str, heading: str, detail: str, status_code: int) -> str:
    content_html = (
        '<section class="panel">'
        f'<p class="eyebrow">HTTP {status_code}</p>'
        f"<h1>{html.escape(heading)}</h1>"
        f"<p>{html.escape(detail)}</p>"
        "</section>"
    )
    return _render_page(
        title=title,
        body_class="message-page",
        page_class="message-page",
        session=None,
        content_html=content_html,
        include_app_js=False,
    )


@dataclass
class AppContext:
    dashboard_service: DashboardService
    auth_host: str
    auth_port: int
    iot_host: str
    iot_port: int


class AppHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, handler_class, context: AppContext):
        super().__init__(server_address, handler_class)
        self.context = context


class WebHandler(BaseHTTPRequestHandler):
    server: AppHTTPServer

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/":
            self._handle_dashboard()
        elif path == "/login":
            self._handle_login_get()
        elif path == "/logout":
            self._handle_logout()
        elif path == "/api/dashboard":
            self._handle_api_dashboard()
        elif path == "/api/sensors":
            self._handle_api_sensors()
        elif path == "/api/alerts":
            self._handle_api_alerts()
        elif path == "/api/measurements":
            self._handle_api_measurements()
        elif path == "/api/status":
            self._handle_api_status()
        elif path.startswith("/static/"):
            self._handle_static(path)
        else:
            if path.startswith("/api/"):
                self._send_json(404, {"error": "Ruta API no encontrada"})
            else:
                self._send_html(
                    404,
                    render_message_page(
                        "Ruta no encontrada",
                        "Pagina no encontrada",
                        "La ruta solicitada no existe en el servidor web del sistema IoT.",
                        404,
                    ),
                )

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/login":
            self._handle_login_post()
        elif path == "/api/actions/refresh":
            self._handle_action_refresh()
        elif path == "/api/actions/acknowledge-alerts":
            self._handle_action_acknowledge_alerts()
        elif path == "/api/commands":
            self._handle_api_commands()
        else:
            if path.startswith("/api/"):
                self._send_json(405, {"error": "Metodo no permitido"})
            else:
                self._send_html(
                    405,
                    render_message_page(
                        "Metodo no permitido",
                        "Metodo no permitido",
                        "La ruta existe, pero no soporta este metodo HTTP.",
                        405,
                    ),
                )

    def log_message(self, format: str, *args) -> None:
        _log("INFO", f"HTTP {self.address_string()} - {format % args}")

    def _handle_dashboard(self) -> None:
        session = self._get_session()
        if not session:
            self._redirect("/login")
            return
        self._send_html(200, render_dashboard_page(session))

    def _handle_login_get(self) -> None:
        session = self._get_session()
        if session:
            self._redirect("/")
            return

        parsed = urlparse(self.path)
        info_msg = "Sesion cerrada correctamente." if "logout=1" in parsed.query else ""
        self._send_html(200, render_login_page(info_msg=info_msg))

    def _handle_login_post(self) -> None:
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        form_data = parse_qs(body)

        username = form_data.get("username", [""])[0].strip()
        password = form_data.get("password", [""])[0]
        if not username or not password:
            self._send_html(400, render_login_page(error_msg="Debes ingresar usuario y contrasena."))
            return

        auth_result = authenticate_user(
            username,
            password,
            self.server.context.auth_host,
            self.server.context.auth_port,
        )
        if auth_result is None:
            self._send_html(401, render_login_page(error_msg="Usuario o contrasena incorrectos."))
            return

        session_id = create_session(auth_result["username"], auth_result["role"])
        self.send_response(302)
        self.send_header("Set-Cookie", f"session_id={session_id}; Path=/; HttpOnly")
        self.send_header("Location", "/")
        self.end_headers()

    def _handle_logout(self) -> None:
        cookie_header = self.headers.get("Cookie", "")
        for part in cookie_header.split(";"):
            part = part.strip()
            if part.startswith("session_id="):
                delete_session(part[len("session_id="):])
                break

        self.send_response(302)
        self.send_header("Set-Cookie", "session_id=; Path=/; Max-Age=0")
        self.send_header("Location", "/login?logout=1")
        self.end_headers()

    def _handle_api_dashboard(self) -> None:
        if not self._require_session():
            return
        self._send_json(200, self.server.context.dashboard_service.get_snapshot())

    def _handle_api_sensors(self) -> None:
        if not self._require_session():
            return
        sensors = self.server.context.dashboard_service.get_sensors()
        self._send_json(200, {"sensors": sensors, "count": len(sensors)})

    def _handle_api_alerts(self) -> None:
        if not self._require_session():
            return
        alerts = self.server.context.dashboard_service.get_alerts()
        self._send_json(200, {"alerts": alerts, "count": len(alerts)})

    def _handle_api_measurements(self) -> None:
        if not self._require_session():
            return
        measurements = self.server.context.dashboard_service.get_measurements()
        self._send_json(200, {"measurements": measurements, "count": len(measurements)})

    def _handle_api_status(self) -> None:
        snapshot = self.server.context.dashboard_service.get_snapshot()
        self._send_json(200, snapshot["system"] | {"updated_at": snapshot["updated_at"]})

    def _handle_action_refresh(self) -> None:
        if not self._require_session():
            return
        self._send_json(200, self.server.context.dashboard_service.refresh_now())

    def _handle_action_acknowledge_alerts(self) -> None:
        if not self._require_session():
            return
        self._send_json(200, self.server.context.dashboard_service.acknowledge_alerts())

    def _handle_api_commands(self) -> None:
        if not self._require_session():
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length).decode("utf-8") if content_length else "{}"
        try:
            payload = json.loads(raw_body) if raw_body else {}
        except json.JSONDecodeError:
            self._send_json(400, {"error": "JSON invalido"})
            return

        command = str(payload.get("command", "")).strip().upper()
        sensor_id = str(payload.get("sensor_id", "")).strip()

        allowed = {"PING", "GET_SENSORS", "GET_LAST"}
        if command not in allowed:
            self._send_json(400, {"error": "Comando no permitido"})
            return

        lines: list[str] = []
        if command == "PING":
            lines = ["PING"]
        elif command == "GET_SENSORS":
            lines = ["GET_SENSORS"]
        elif command == "GET_LAST":
            if not sensor_id:
                self._send_json(400, {"error": "sensor_id es requerido para GET_LAST"})
                return
            lines = [f"GET_LAST {sensor_id}"]

        try:
            result = run_iot_commands(
                iot_host=self.server.context.iot_host,
                iot_port=self.server.context.iot_port,
                commands=lines,
            )
        except Exception as exc:
            self._send_json(502, {"error": str(exc)})
            return

        self._send_json(200, result)

    def _handle_static(self, path: str) -> None:
        relative = path[len("/static/") :]
        file_path = (STATIC_DIR / relative).resolve()
        if STATIC_DIR not in file_path.parents or not file_path.is_file():
            self._send_html(
                404,
                render_message_page(
                    "Asset no encontrado",
                    "Recurso estatico no encontrado",
                    "El archivo solicitado no existe en el directorio static.",
                    404,
                ),
            )
            return

        content_type, _ = mimetypes.guess_type(file_path.name)
        if not content_type:
            content_type = "application/octet-stream"
        body = file_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8" if content_type.startswith("text/") or content_type.endswith("javascript") else content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _get_session(self) -> dict | None:
        cookie_header = self.headers.get("Cookie", "")
        for part in cookie_header.split(";"):
            part = part.strip()
            if part.startswith("session_id="):
                return get_session(part[len("session_id="):])
        return None

    def _require_session(self) -> bool:
        if self._get_session():
            return True
        self._send_json(401, {"error": "No autenticado"})
        return False

    def _redirect(self, location: str) -> None:
        self.send_response(302)
        self.send_header("Location", location)
        self.end_headers()

    def _send_html(self, status_code: int, html_body: str) -> None:
        body = html_body.encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status_code: int, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def run_server(
    *,
    web_host: str,
    web_port: int,
    iot_host: str,
    iot_port: int,
    auth_host: str,
    auth_port: int,
) -> int:
    snapshot_client = IotClient(iot_host, iot_port, timeout=5.0, log_fn=_log)
    alert_client = IotClient(iot_host, iot_port, timeout=20.0, log_fn=_log)
    alert_feed = AlertFeed(alert_client, log_fn=_log)
    dashboard_service = DashboardService(snapshot_client, alert_feed, refresh_interval=5.0, log_fn=_log)
    dashboard_service.start()

    context = AppContext(
        dashboard_service=dashboard_service,
        auth_host=auth_host,
        auth_port=auth_port,
        iot_host=iot_host,
        iot_port=iot_port,
    )

    server = AppHTTPServer((web_host, web_port), WebHandler, context)
    _log("INFO", f"Servidor web iniciado en http://{web_host}:{web_port}")
    _log("INFO", f"Servidor IoT configurado en {iot_host}:{iot_port}")
    _log("INFO", f"Servicio de autenticacion configurado en {auth_host}:{auth_port}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        _log("INFO", "Deteniendo servidor web por interrupcion del usuario")
    finally:
        server.server_close()
        dashboard_service.stop()

    return 0


def run_iot_commands(*, iot_host: str, iot_port: int, commands: list[str]) -> dict:
    client = IotClient(iot_host, iot_port, timeout=8.0, log_fn=_log)
    sock, reader = client.open_operator_connection()
    transcript: list[dict] = []
    try:
        for line in commands:
            client._send_line(sock, line)
            response = client._read_line(reader)
            transcript.append({"command": line, "response": response})
    finally:
        client._close_connection(sock, reader)

    return {
        "iot_host": iot_host,
        "iot_port": iot_port,
        "transcript": transcript,
        "count": len(transcript),
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Servidor web del sistema distribuido de monitoreo IoT.")
    parser.add_argument(
        "--web-host",
        default=os.getenv("WEB_HOST", "0.0.0.0"),
        help="Host o interfaz del servidor web.",
    )
    parser.add_argument(
        "--web-port",
        type=int,
        default=int(os.getenv("WEB_PORT", "8080")),
        help="Puerto HTTP del servidor web.",
    )
    parser.add_argument(
        "--iot-host",
        default=os.getenv("IOT_SERVER_HOST", "localhost"),
        help="Nombre de host del servidor IoT.",
    )
    parser.add_argument(
        "--iot-port",
        type=int,
        default=int(os.getenv("IOT_SERVER_PORT", "9000")),
        help="Puerto TCP del servidor IoT.",
    )
    parser.add_argument(
        "--auth-host",
        default=os.getenv("AUTH_HOST", "localhost"),
        help="Nombre de host del servicio externo de autenticacion.",
    )
    parser.add_argument(
        "--auth-port",
        type=int,
        default=int(os.getenv("AUTH_PORT", "8081")),
        help="Puerto HTTP del servicio de autenticacion.",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return run_server(
        web_host=args.web_host,
        web_port=args.web_port,
        iot_host=args.iot_host,
        iot_port=args.iot_port,
        auth_host=args.auth_host,
        auth_port=args.auth_port,
    )


if __name__ == "__main__":
    raise SystemExit(main())
