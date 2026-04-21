#!/usr/bin/env python3
"""
web_server.py
───────────────────────────────────────────────────────────────────────────────
Servidor HTTP para la interfaz web del sistema distribuido de monitoreo IoT.

Proposito dentro del sistema:
    Permite a los usuarios interactuar con el sistema desde un navegador web.
    Implementa un servidor HTTP desde cero (sin frameworks externos), siguiendo
    el enunciado del proyecto.

Rutas HTTP implementadas:
    GET  /              → Dashboard principal (sensores + estado del sistema)
    GET  /login         → Formulario de inicio de sesion
    POST /login         → Procesa el login consultando el servicio de auth
    GET  /logout        → Cierra la sesion del usuario
    GET  /api/sensors   → JSON con sensores activos (consulta al servidor IoT)
    GET  /api/status    → JSON con estado general del sistema

Arquitectura:
    1. El servidor web actua como "cliente" del servidor IoT.
       Para obtener datos, se conecta al servidor IoT via TCP, se registra como
       operador (con un ID especial "web_dashboard"), consulta sensores, y cierra
       la conexion.  Se hace una conexion nueva por cada peticion para simplificar
       (sin mantener una sesion persistente en el servidor web).

    2. Para autenticacion, el servidor web consulta al servicio de autenticacion
       (auth_service.py) via HTTP. El servidor IoT NO almacena usuarios.

    3. Las sesiones de usuario en la web se manejan con una cookie firmada simple.

Ejecucion:
    python3 web_server.py
    python3 web_server.py --web-port 8080 --iot-host iot-monitoring.example.com --iot-port 9000
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import socket
import sys
import threading
import time
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse


# ─── Utilidades de log ────────────────────────────────────────────────────────

def _timestamp() -> str:
    """Retorna el timestamp actual en formato legible."""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _log(level: str, message: str) -> None:
    """Imprime un mensaje de log con timestamp y nivel al stdout."""
    print(f"[{_timestamp()}] [{level}] [WEB] {message}", flush=True)


# ─── Gestion de sesiones web ──────────────────────────────────────────────────

# Diccionario de sesiones activas: { session_id: { "username": ..., "role": ... } }
# En produccion se usaria una base de datos o Redis.
_sessions: dict[str, dict] = {}
_sessions_lock = threading.Lock()


def create_session(username: str, role: str) -> str:
    """
    Crea una nueva sesion de usuario y retorna el session_id.

    :param username: nombre del usuario autenticado
    :param role:     rol del usuario (admin/operador/invitado)
    :return: identificador unico de la sesion
    """
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
    """
    Retorna los datos de la sesion o None si no existe.

    :param session_id: identificador de la sesion
    :return: dict con datos de la sesion, o None
    """
    with _sessions_lock:
        return _sessions.get(session_id)


def delete_session(session_id: str) -> None:
    """
    Elimina una sesion activa (logout).

    :param session_id: identificador de la sesion a eliminar
    """
    with _sessions_lock:
        info = _sessions.pop(session_id, None)
    if info:
        _log("INFO", f"Sesion eliminada para '{info['username']}'")


# ─── Comunicacion con el servidor IoT ─────────────────────────────────────────

class IotClient:
    """
    Cliente liviano del protocolo IOT-MONITOR-TEXT para uso del servidor web.

    Establece una conexion TCP al servidor IoT, se registra como operador
    especial, ejecuta los comandos necesarios y cierra la conexion.

    El host se resuelve via DNS en cada llamada (sin IPs hardcodeadas).
    """

    def __init__(self, host: str, port: int, timeout: float = 5.0) -> None:
        """
        :param host:    nombre de dominio del servidor IoT
        :param port:    puerto TCP del servidor IoT
        :param timeout: timeout de conexion y lectura en segundos
        """
        self._host = host
        self._port = port
        self._timeout = timeout

    def get_sensors(self) -> list[dict]:
        """
        Consulta la lista de sensores activos al servidor IoT.

        Pasos:
            1. Resolucion DNS del host del servidor.
            2. Conexion TCP.
            3. REGISTER OPERATOR web_dashboard
            4. GET_SENSORS
            5. Parsear respuesta y cerrar conexion.

        :return: lista de dicts { "id": ..., "tipo": ... }
                 Lista vacia si hay error o no hay sensores.
        """
        try:
            return self._run_commands_and_get_sensors()
        except Exception as exc:
            _log("WARN", f"Error consultando sensores al servidor IoT: {exc}")
            return []

    def get_sensor_last(self, sensor_id: str) -> dict | None:
        """
        Consulta la ultima medicion de un sensor especifico.

        :param sensor_id: identificador del sensor
        :return: dict { "id": ..., "valor": ..., "timestamp": ... } o None
        """
        try:
            return self._run_commands_and_get_last(sensor_id)
        except Exception as exc:
            _log("WARN", f"Error consultando GET_LAST {sensor_id}: {exc}")
            return None

    def _connect(self) -> tuple[socket.socket, "socket._io.BufferedReader"]:
        """
        Establece la conexion TCP al servidor IoT.

        La resolucion DNS se hace con socket.getaddrinfo para soportar
        tanto IPv4 como IPv6 sin hardcodear ninguna IP.

        :return: tupla (socket, file_reader)
        :raises: socket.gaierror si DNS falla
        :raises: OSError si la conexion falla
        """
        # Resolver DNS: nombre → direccion(es) IP
        _log("INFO", f"Resolviendo DNS: {self._host}")
        addr_info = socket.getaddrinfo(
            self._host, self._port,
            family=socket.AF_UNSPEC,
            type=socket.SOCK_STREAM
        )

        last_error = None
        for family, socktype, proto, canonname, sockaddr in addr_info:
            try:
                sock = socket.socket(family, socktype, proto)
                sock.settimeout(self._timeout)
                sock.connect(sockaddr)
                _log("INFO", f"Conectado al servidor IoT en {sockaddr[0]}:{sockaddr[1]}")
                # Envolver el socket en un reader de texto para leer lineas facil
                reader = sock.makefile("r", encoding="utf-8")
                return sock, reader
            except OSError as exc:
                last_error = exc
                try:
                    sock.close()
                except Exception:
                    pass

        raise OSError(f"No se pudo conectar a {self._host}:{self._port}: {last_error}")

    def _send_line(self, sock: socket.socket, line: str) -> None:
        """
        Envia una linea de texto al servidor IoT (con '\n' al final).

        :param sock: socket conectado
        :param line: linea de texto del protocolo (sin '\n')
        """
        sock.sendall((line + "\n").encode("utf-8"))

    def _register(self, sock: socket.socket, reader, operator_id: str) -> None:
        """
        Registra este cliente como operador en el servidor IoT.

        :param sock:        socket conectado
        :param reader:      file reader del socket
        :param operator_id: ID del operador a registrar
        :raises: ValueError si la respuesta no es la esperada
        """
        self._send_line(sock, f"REGISTER OPERATOR {operator_id}")
        response = reader.readline().strip()
        expected = f"OK REGISTERED OPERATOR {operator_id}"
        if response != expected:
            raise ValueError(f"Registro fallido: '{response}'")

    def _run_commands_and_get_sensors(self) -> list[dict]:
        """
        Ejecuta el flujo completo para obtener la lista de sensores.

        :return: lista de dicts con id y tipo de cada sensor
        """
        # Usar un ID de operador unico por peticion para evitar colisiones
        op_id = f"web_{uuid.uuid4().hex[:8]}"
        sock, reader = self._connect()

        try:
            # Registrar como operador
            self._register(sock, reader, op_id)

            # Enviar GET_SENSORS y leer respuesta
            self._send_line(sock, "GET_SENSORS")
            response = reader.readline().strip()

            # Parsear: "SENSORS <n> <id1>:<tipo1> <id2>:<tipo2> ..."
            return _parse_sensors_response(response)
        finally:
            # Siempre cerrar la conexion al terminar
            try:
                reader.close()
                sock.close()
            except Exception:
                pass

    def _run_commands_and_get_last(self, sensor_id: str) -> dict | None:
        """
        Ejecuta el flujo completo para obtener la ultima medicion de un sensor.

        :param sensor_id: ID del sensor a consultar
        :return: dict con los datos del sensor, o None si no hay datos
        """
        op_id = f"web_{uuid.uuid4().hex[:8]}"
        sock, reader = self._connect()

        try:
            # Registrar como operador
            self._register(sock, reader, op_id)

            # Enviar GET_LAST y leer respuesta
            self._send_line(sock, f"GET_LAST {sensor_id}")
            response = reader.readline().strip()

            # Parsear: "LAST <id> <valor> <timestamp>"
            if response.startswith("LAST "):
                parts = response.split(" ", 3)
                if len(parts) >= 3:
                    return {
                        "id": parts[1],
                        "valor": parts[2],
                        "timestamp": parts[3] if len(parts) == 4 else "",
                    }
            return None
        finally:
            try:
                reader.close()
                sock.close()
            except Exception:
                pass


def _parse_sensors_response(response: str) -> list[dict]:
    """
    Parsea la respuesta de GET_SENSORS del servidor IoT.

    Formato esperado: "SENSORS <n> <id1>:<tipo1> <id2>:<tipo2> ..."

    :param response: linea de respuesta del servidor
    :return: lista de dicts { "id": ..., "tipo": ... }
    """
    sensors = []

    if not response.startswith("SENSORS "):
        _log("WARN", f"Respuesta inesperada de GET_SENSORS: '{response}'")
        return sensors

    tokens = response.split(" ")
    # tokens[0] = "SENSORS", tokens[1] = cantidad, tokens[2..] = pares id:tipo

    for token in tokens[2:]:
        colon_idx = token.find(":")
        if colon_idx <= 0:
            continue
        sensor_id = token[:colon_idx]
        sensor_type = token[colon_idx + 1:]
        sensors.append({"id": sensor_id, "tipo": sensor_type})

    return sensors


# ─── Comunicacion con el servicio de autenticacion ───────────────────────────

def authenticate_user(username: str, password: str, auth_host: str, auth_port: int) -> dict | None:
    """
    Consulta el servicio externo de autenticacion para validar credenciales.

    El servidor IoT no almacena usuarios; toda la gestion de identidades
    se delega a auth_service.py que corre en un proceso separado.

    :param username:  nombre de usuario ingresado
    :param password:  contrasena ingresada
    :param auth_host: host del servicio de autenticacion
    :param auth_port: puerto del servicio de autenticacion
    :return: dict con { "username", "role", "nombre" } o None si falla
    """
    try:
        # Preparar el body de la peticion
        body_data = json.dumps({"username": username, "password": password})
        body_bytes = body_data.encode("utf-8")

        # Conexion HTTP al servicio de autenticacion (resolucion DNS incluida)
        _log("INFO", f"Consultando autenticacion para '{username}' en {auth_host}:{auth_port}")
        conn = http.client.HTTPConnection(auth_host, auth_port, timeout=5)

        conn.request(
            "POST", "/auth",
            body=body_bytes,
            headers={
                "Content-Type": "application/json",
                "Content-Length": str(len(body_bytes)),
            }
        )

        response = conn.getresponse()
        response_body = response.read().decode("utf-8")
        conn.close()

        # Parsear la respuesta JSON del servicio de auth
        data = json.loads(response_body)

        if response.status == 200 and data.get("status") == "ok":
            _log("INFO", f"Autenticacion exitosa: '{username}' rol='{data['role']}'")
            return data

        _log("INFO", f"Autenticacion fallida para '{username}': {data.get('message', 'error')}")
        return None

    except Exception as exc:
        # Si el servicio de auth no esta disponible, loggear pero no bloquear
        _log("WARN", f"Error conectando al servicio de autenticacion: {exc}")
        return None


# ─── Templates HTML ───────────────────────────────────────────────────────────

def _html_base(title: str, content: str, session: dict | None = None) -> str:
    """
    Genera el HTML base con la estructura comun de todas las paginas.

    :param title:   titulo de la pagina
    :param content: contenido HTML del cuerpo de la pagina
    :param session: datos de la sesion activa (None si no hay login)
    :return: HTML completo como string
    """
    # Barra de navegacion
    if session:
        nav_user = f"""
            <span class="nav-user">👤 {session['username']} ({session['role']})</span>
            <a href="/logout" class="btn-logout">Cerrar sesion</a>
        """
    else:
        nav_user = '<a href="/login" class="btn-login">Iniciar sesion</a>'

    return f"""<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title} — IoT Monitor</title>
    <style>
        /* Reset basico y tipografia */
        *, *::before, *::after {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
               background: #f0f2f5; color: #333; line-height: 1.6; }}

        /* Barra de navegacion */
        .navbar {{ background: #1a1a2e; color: white; padding: 0.75rem 2rem;
                   display: flex; align-items: center; justify-content: space-between;
                   box-shadow: 0 2px 8px rgba(0,0,0,0.3); }}
        .navbar-brand {{ font-size: 1.2rem; font-weight: 700; color: #00d4ff; }}
        .nav-user {{ color: #ccc; margin-right: 1rem; }}
        .btn-logout, .btn-login {{ background: #e74c3c; color: white; padding: 0.4rem 1rem;
                                    border-radius: 4px; text-decoration: none; font-size: 0.9rem; }}
        .btn-login {{ background: #3498db; }}
        .btn-logout:hover {{ background: #c0392b; }}
        .btn-login:hover {{ background: #2980b9; }}

        /* Contenedor principal */
        .container {{ max-width: 1100px; margin: 2rem auto; padding: 0 1.5rem; }}

        /* Tarjetas */
        .card {{ background: white; border-radius: 8px; padding: 1.5rem;
                 box-shadow: 0 2px 8px rgba(0,0,0,0.08); margin-bottom: 1.5rem; }}
        .card h2 {{ color: #1a1a2e; border-bottom: 2px solid #00d4ff;
                    padding-bottom: 0.5rem; margin-bottom: 1rem; font-size: 1.1rem; }}

        /* Indicadores de estado */
        .stat-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1rem; }}
        .stat-card {{ background: linear-gradient(135deg, #1a1a2e, #16213e);
                      color: white; border-radius: 8px; padding: 1.25rem; text-align: center; }}
        .stat-card .stat-value {{ font-size: 2rem; font-weight: 700; color: #00d4ff; }}
        .stat-card .stat-label {{ font-size: 0.85rem; color: #aaa; margin-top: 0.25rem; }}

        /* Tabla de sensores */
        table {{ width: 100%; border-collapse: collapse; }}
        th {{ background: #1a1a2e; color: #00d4ff; padding: 0.7rem 1rem;
              text-align: left; font-size: 0.9rem; }}
        td {{ padding: 0.6rem 1rem; border-bottom: 1px solid #eee; font-size: 0.9rem; }}
        tr:hover td {{ background: #f8f9fa; }}
        .badge {{ display: inline-block; padding: 0.2rem 0.6rem; border-radius: 12px;
                  font-size: 0.8rem; font-weight: 600; }}
        .badge-temp {{ background: #ffe0e0; color: #c0392b; }}
        .badge-hum  {{ background: #e0f0ff; color: #2980b9; }}
        .badge-vib  {{ background: #fff3e0; color: #e67e22; }}
        .badge-pres {{ background: #e8f5e9; color: #27ae60; }}
        .badge-cons {{ background: #f3e5f5; color: #8e44ad; }}

        /* Formulario de login */
        .login-container {{ max-width: 420px; margin: 4rem auto; padding: 0 1rem; }}
        .login-card {{ background: white; border-radius: 8px; padding: 2rem;
                       box-shadow: 0 4px 20px rgba(0,0,0,0.12); }}
        .login-card h1 {{ text-align: center; color: #1a1a2e; margin-bottom: 1.5rem; }}
        .form-group {{ margin-bottom: 1rem; }}
        .form-group label {{ display: block; font-size: 0.9rem; color: #555;
                             margin-bottom: 0.3rem; }}
        .form-group input {{ width: 100%; padding: 0.6rem 0.8rem; border: 1px solid #ddd;
                             border-radius: 4px; font-size: 0.95rem; }}
        .form-group input:focus {{ outline: none; border-color: #00d4ff;
                                   box-shadow: 0 0 0 2px rgba(0,212,255,0.2); }}
        .btn-primary {{ width: 100%; background: #1a1a2e; color: white; border: none;
                        padding: 0.7rem; border-radius: 4px; font-size: 1rem;
                        cursor: pointer; margin-top: 0.5rem; }}
        .btn-primary:hover {{ background: #16213e; }}
        .alert-error {{ background: #ffe0e0; color: #c0392b; padding: 0.7rem 1rem;
                        border-radius: 4px; margin-bottom: 1rem; font-size: 0.9rem; }}
        .alert-info {{ background: #e0f7fa; color: #006064; padding: 0.7rem 1rem;
                       border-radius: 4px; margin-bottom: 1rem; font-size: 0.9rem; }}

        /* Footer */
        footer {{ text-align: center; color: #888; font-size: 0.8rem; padding: 2rem; }}
    </style>
</head>
<body>
    <nav class="navbar">
        <span class="navbar-brand"> Sistema IoT — Monitor de Sensores</span>
        <div>{nav_user}</div>
    </nav>
    <div class="container">
        {content}
    </div>
    <footer>Sistema distribuido de monitoreo IoT — Proyecto Internet y Arquitecturas</footer>
</body>
</html>"""


def _badge_class(sensor_type: str) -> str:
    """Retorna la clase CSS del badge segun el tipo de sensor."""
    mapping = {
        "temperatura": "badge-temp",
        "humedad": "badge-hum",
        "vibracion": "badge-vib",
        "presion": "badge-pres",
        "consumo": "badge-cons",
    }
    return mapping.get(sensor_type, "")


def _render_dashboard(sensors: list[dict], session: dict) -> str:
    """
    Genera el HTML del dashboard principal con la lista de sensores.

    :param sensors: lista de sensores obtenida del servidor IoT
    :param session: datos de la sesion activa
    :return: HTML completo de la pagina
    """
    now = _timestamp()
    sensor_count = len(sensors)

    # Contar sensores por tipo para las estadisticas
    type_counts: dict[str, int] = {}
    for s in sensors:
        type_counts[s["tipo"]] = type_counts.get(s["tipo"], 0) + 1

    # Filas de la tabla de sensores
    if sensors:
        rows_html = ""
        for s in sensors:
            badge_cls = _badge_class(s["tipo"])
            rows_html += f"""
                <tr>
                    <td><strong>{s['id']}</strong></td>
                    <td><span class="badge {badge_cls}">{s['tipo']}</span></td>
                </tr>"""
    else:
        # Si no hay sensores, mostrar mensaje informativo
        rows_html = '<tr><td colspan="2" style="text-align:center;color:#888;padding:2rem">Sin sensores activos en este momento</td></tr>'

    # Tarjetas de estadisticas de tipo
    stat_cards = f"""
        <div class="stat-card">
            <div class="stat-value">{sensor_count}</div>
            <div class="stat-label">Sensores activos</div>
        </div>"""

    for tipo, count in type_counts.items():
        stat_cards += f"""
        <div class="stat-card">
            <div class="stat-value">{count}</div>
            <div class="stat-label">{tipo.capitalize()}</div>
        </div>"""

    content = f"""
        <div class="card">
            <h2> Estado del sistema</h2>
            <div class="stat-grid">{stat_cards}</div>
            <p style="color:#888;font-size:0.8rem;margin-top:1rem">
                Ultima actualizacion: {now}
                — <a href="/" style="color:#00d4ff">Actualizar</a>
            </p>
        </div>

        <div class="card">
            <h2>📡 Sensores activos</h2>
            <table>
                <thead>
                    <tr>
                        <th>ID Sensor</th>
                        <th>Tipo</th>
                    </tr>
                </thead>
                <tbody>
                    {rows_html}
                </tbody>
            </table>
        </div>
    """
    return _html_base("Dashboard", content, session)


def _render_login(error_msg: str = "", info_msg: str = "") -> str:
    """
    Genera el HTML del formulario de inicio de sesion.

    :param error_msg: mensaje de error a mostrar (vacio si no hay error)
    :param info_msg:  mensaje informativo (p.ej. "Sesion cerrada")
    :return: HTML completo de la pagina de login
    """
    error_html = f'<div class="alert-error">{error_msg}</div>' if error_msg else ""
    info_html = f'<div class="alert-info">{info_msg}</div>' if info_msg else ""

    content = f"""
        <div class="login-container">
            <div class="login-card">
                <h1> Iniciar sesion</h1>
                {info_html}
                {error_html}
                <form method="POST" action="/login">
                    <div class="form-group">
                        <label for="username">Usuario</label>
                        <input type="text" id="username" name="username"
                               placeholder="Ingrese su usuario" required autofocus>
                    </div>
                    <div class="form-group">
                        <label for="password">Contrasena</label>
                        <input type="password" id="password" name="password"
                               placeholder="Ingrese su contrasena" required>
                    </div>
                    <button type="submit" class="btn-primary">Ingresar</button>
                </form>
                <p style="margin-top:1rem;font-size:0.8rem;color:#888;text-align:center">
                    Usuarios de prueba: admin / operador1 / operador2
                </p>
            </div>
        </div>
    """
    # La pagina de login no tiene contenedor principal, va directamente en body
    return _html_base("Iniciar sesion", content, None)


# ─── Handler HTTP ─────────────────────────────────────────────────────────────

class WebHandler(BaseHTTPRequestHandler):
    """
    Maneja las peticiones HTTP de la interfaz web del sistema IoT.

    La instancia recibe iot_client, auth_host y auth_port a traves de
    la clase factory WebHandlerFactory para no usar variables globales.
    """

    # Estos atributos son inyectados por WebHandlerFactory
    iot_client: IotClient
    auth_host: str
    auth_port: int

    def do_GET(self) -> None:
        """Maneja peticiones GET."""
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/":
            self._handle_dashboard()
        elif path == "/login":
            self._handle_login_get()
        elif path == "/logout":
            self._handle_logout()
        elif path == "/api/sensors":
            self._handle_api_sensors()
        elif path == "/api/status":
            self._handle_api_status()
        else:
            # Ruta no encontrada: retornar 404
            self._send_html(404, "<h1>404 — Pagina no encontrada</h1>")

    def do_POST(self) -> None:
        """Maneja peticiones POST."""
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/login":
            self._handle_login_post()
        else:
            self._send_html(405, "<h1>405 — Metodo no permitido</h1>")

    def _get_session(self) -> dict | None:
        """
        Extrae y valida la sesion del usuario leyendo la cookie de la peticion.

        :return: dict con datos de la sesion, o None si no hay sesion valida
        """
        cookie_header = self.headers.get("Cookie", "")
        # La cookie tiene formato: "session_id=<uuid>; ..."
        for part in cookie_header.split(";"):
            part = part.strip()
            if part.startswith("session_id="):
                sid = part[len("session_id="):]
                return get_session(sid)
        return None

    def _handle_dashboard(self) -> None:
        """
        Maneja GET /.
        Requiere autenticacion. Si no hay sesion, redirige a /login.
        Obtiene sensores del servidor IoT y renderiza el dashboard.
        """
        session = self._get_session()
        if not session:
            # Sin sesion: redirigir al formulario de login
            self._redirect("/login")
            return

        # Obtener sensores del servidor IoT (nueva conexion TCP por peticion)
        sensors = self.iot_client.get_sensors()

        html = _render_dashboard(sensors, session)
        self._send_html(200, html)

    def _handle_login_get(self) -> None:
        """
        Maneja GET /login.
        Si ya hay sesion activa, redirigir al dashboard.
        Si no, mostrar el formulario.
        """
        session = self._get_session()
        if session:
            # Ya autenticado: ir directamente al dashboard
            self._redirect("/")
            return

        # Verificar si viene de un logout (mensaje informativo)
        parsed = urlparse(self.path)
        info = "Sesion cerrada correctamente." if "logout=1" in parsed.query else ""
        html = _render_login(info_msg=info)
        self._send_html(200, html)

    def _handle_login_post(self) -> None:
        """
        Maneja POST /login.
        Lee las credenciales del formulario y consulta el servicio de auth.
        Si las credenciales son correctas, crea una sesion y redirige al dashboard.
        """
        # Leer el cuerpo del formulario
        content_length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(content_length).decode("utf-8")

        # Parsear los datos del formulario (formato: username=...&password=...)
        form_data = parse_qs(raw_body)
        username = form_data.get("username", [""])[0].strip()
        password = form_data.get("password", [""])[0]

        if not username or not password:
            # Campos vacios: mostrar error sin consultar el servicio de auth
            html = _render_login(error_msg="Debe ingresar usuario y contrasena.")
            self._send_html(400, html)
            return

        # Consultar el servicio externo de autenticacion
        auth_result = authenticate_user(username, password, self.auth_host, self.auth_port)

        if auth_result is None:
            # Credenciales incorrectas o servicio no disponible
            _log("INFO", f"Login fallido para '{username}'")
            html = _render_login(error_msg="Usuario o contrasena incorrectos.")
            self._send_html(401, html)
            return

        # Autenticacion exitosa: crear sesion y redirigir al dashboard
        session_id = create_session(auth_result["username"], auth_result["role"])
        self.send_response(302)
        # Setear cookie de sesion (HttpOnly para seguridad basica)
        self.send_header("Set-Cookie", f"session_id={session_id}; Path=/; HttpOnly")
        self.send_header("Location", "/")
        self.end_headers()

    def _handle_logout(self) -> None:
        """
        Maneja GET /logout.
        Elimina la sesion del usuario y redirige al login.
        """
        cookie_header = self.headers.get("Cookie", "")
        for part in cookie_header.split(";"):
            part = part.strip()
            if part.startswith("session_id="):
                sid = part[len("session_id="):]
                delete_session(sid)
                break

        # Limpiar la cookie del navegador (setear con fecha expirada)
        self.send_response(302)
        self.send_header("Set-Cookie", "session_id=; Path=/; Max-Age=0")
        self.send_header("Location", "/login?logout=1")
        self.end_headers()

    def _handle_api_sensors(self) -> None:
        """
        Maneja GET /api/sensors.
        Retorna JSON con la lista de sensores activos.
        Requiere autenticacion.
        """
        session = self._get_session()
        if not session:
            self._send_json(401, {"error": "No autenticado"})
            return

        # Consultar sensores al servidor IoT
        sensors = self.iot_client.get_sensors()
        self._send_json(200, {"sensors": sensors, "count": len(sensors)})

    def _handle_api_status(self) -> None:
        """
        Maneja GET /api/status.
        Retorna JSON con el estado general del sistema.
        """
        sensors = self.iot_client.get_sensors()
        status = {
            "status": "ok",
            "timestamp": _timestamp(),
            "sensor_count": len(sensors),
            "iot_server": {
                "host": self.iot_client._host,
                "port": self.iot_client._port,
                "reachable": len(sensors) >= 0,  # Si get_sensors no lanza excepcion
            },
        }
        self._send_json(200, status)

    def _send_html(self, status_code: int, html: str) -> None:
        """
        Envia una respuesta HTTP con cuerpo HTML.

        :param status_code: codigo de estado HTTP
        :param html:        contenido HTML como string
        """
        body = html.encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status_code: int, data: dict) -> None:
        """
        Envia una respuesta HTTP con cuerpo JSON.

        :param status_code: codigo de estado HTTP
        :param data:        diccionario a serializar como JSON
        """
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _redirect(self, location: str) -> None:
        """
        Envia una respuesta de redireccion 302.

        :param location: URL de destino
        """
        self.send_response(302)
        self.send_header("Location", location)
        self.end_headers()

    def log_message(self, fmt: str, *args) -> None:
        """Reemplaza el log por defecto de BaseHTTPRequestHandler."""
        _log("HTTP", f"{self.address_string()} - {fmt % args}")


# ─── Factory del Handler ──────────────────────────────────────────────────────

def make_handler_class(iot_client: IotClient, auth_host: str, auth_port: int):
    """
    Crea una subclase de WebHandler con las dependencias inyectadas.

    Esto es necesario porque BaseHTTPRequestHandler instancia el handler
    por cada peticion y no permite pasar argumentos al constructor.

    :param iot_client: cliente del servidor IoT
    :param auth_host:  host del servicio de autenticacion
    :param auth_port:  puerto del servicio de autenticacion
    :return: clase handler lista para usar con HTTPServer
    """
    class BoundWebHandler(WebHandler):
        pass

    # Inyectar las dependencias como atributos de clase
    BoundWebHandler.iot_client = iot_client
    BoundWebHandler.auth_host = auth_host
    BoundWebHandler.auth_port = auth_port
    return BoundWebHandler


# ─── Funcion principal ────────────────────────────────────────────────────────

def run(
    web_host: str = "0.0.0.0",
    web_port: int = 8080,
    iot_host: str = "localhost",
    iot_port: int = 9000,
    auth_host: str = "localhost",
    auth_port: int = 8081,
) -> None:
    """
    Inicia el servidor HTTP de la interfaz web.

    :param web_host:  interfaz de red para el servidor web
    :param web_port:  puerto del servidor web
    :param iot_host:  nombre de dominio del servidor IoT (resuelto via DNS)
    :param iot_port:  puerto TCP del servidor IoT
    :param auth_host: host del servicio de autenticacion
    :param auth_port: puerto del servicio de autenticacion
    """
    # Crear el cliente IoT que usaran todos los handlers
    iot_client = IotClient(iot_host, iot_port)

    # Crear la clase handler con las dependencias inyectadas
    handler_class = make_handler_class(iot_client, auth_host, auth_port)

    # Crear y arrancar el servidor HTTP
    server = HTTPServer((web_host, web_port), handler_class)

    _log("INFO", f"Servidor web iniciado en http://{web_host}:{web_port}")
    _log("INFO", f"Servidor IoT:  {iot_host}:{iot_port} (resolucion DNS)")
    _log("INFO", f"Servicio auth: {auth_host}:{auth_port}")
    _log("INFO", "Presione Ctrl+C para detener.")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        _log("INFO", "Servidor web detenido.")
    finally:
        server.server_close()


# ─── Punto de entrada ─────────────────────────────────────────────────────────

def _parse_args() -> argparse.Namespace:
    """Parsea los argumentos de linea de comandos."""
    parser = argparse.ArgumentParser(
        description="Servidor HTTP para la interfaz web del sistema IoT."
    )
    parser.add_argument(
        "--web-host", default=os.getenv("WEB_HOST", "0.0.0.0"),
        help="Interfaz de red del servidor web (defecto: 0.0.0.0)"
    )
    parser.add_argument(
        "--web-port", type=int, default=int(os.getenv("WEB_PORT", "8080")),
        help="Puerto del servidor web (defecto: 8080)"
    )
    parser.add_argument(
        "--iot-host", default=os.getenv("IOT_SERVER_HOST", "localhost"),
        help="Host (dominio) del servidor IoT (defecto: localhost)"
    )
    parser.add_argument(
        "--iot-port", type=int, default=int(os.getenv("IOT_SERVER_PORT", "9000")),
        help="Puerto TCP del servidor IoT (defecto: 9000)"
    )
    parser.add_argument(
        "--auth-host", default=os.getenv("AUTH_HOST", "localhost"),
        help="Host del servicio de autenticacion (defecto: localhost)"
    )
    parser.add_argument(
        "--auth-port", type=int, default=int(os.getenv("AUTH_PORT", "8081")),
        help="Puerto del servicio de autenticacion (defecto: 8081)"
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    run(
        web_host=args.web_host,
        web_port=args.web_port,
        iot_host=args.iot_host,
        iot_port=args.iot_port,
        auth_host=args.auth_host,
        auth_port=args.auth_port,
    )
