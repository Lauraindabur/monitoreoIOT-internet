#!/usr/bin/env python3
"""
auth_service.py
───────────────────────────────────────────────────────────────────────────────
Servicio externo de autenticacion para el sistema IoT.

Proposito dentro del sistema:
    El servidor principal NO almacena usuarios localmente. Cuando alguien
    intenta iniciar sesion en la interfaz web, el servidor web consulta ESTE
    servicio para verificar credenciales y obtener el rol del usuario.

    Esto simula una arquitectura real donde la gestion de identidades esta
    separada del servidor de negocio principal.

Protocolo HTTP expuesto:
    POST /auth
        Body (JSON): { "username": "...", "password": "..." }
        Respuesta 200 OK: { "status": "ok", "username": "...", "role": "..." }
        Respuesta 401:    { "status": "error", "message": "Credenciales invalidas" }

    GET /health
        Respuesta 200: { "status": "ok", "service": "auth" }

Ejecucion:
    python3 auth_service.py
    python3 auth_service.py --host 0.0.0.0 --port 8081
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime


# ─── Base de usuarios simulada ────────────────────────────────────────────────
# En un sistema de produccion esto seria una base de datos (PostgreSQL, etc.).
# Los roles posibles son: "admin" y "operador".
# El servidor web consulta este servicio; los usuarios NO se almacenan en el
# servidor principal de monitoreo.
USERS: dict[str, dict] = {
    "admin": {
        "password": "admin123",
        "role": "admin",
        "nombre": "Administrador del Sistema",
    },
    "operador1": {
        "password": "oper123",
        "role": "operador",
        "nombre": "Operador Principal",
    },
    "operador2": {
        "password": "oper456",
        "role": "operador",
        "nombre": "Operador Secundario",
    },
    "invitado": {
        "password": "guest",
        "role": "invitado",
        "nombre": "Usuario de Solo Lectura",
    },
}


def _timestamp() -> str:
    """Retorna el timestamp actual en formato ISO-like para los logs."""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _log(level: str, message: str) -> None:
    """Imprime un mensaje de log con timestamp al stdout."""
    print(f"[{_timestamp()}] [{level}] [AUTH] {message}", flush=True)


# ─── Handler HTTP ─────────────────────────────────────────────────────────────

class AuthHandler(BaseHTTPRequestHandler):
    """
    Maneja las peticiones HTTP del servicio de autenticacion.

    Rutas implementadas:
        POST /auth    → Validar credenciales y retornar rol.
        GET  /health  → Verificar que el servicio esta activo.
    """

    def do_POST(self) -> None:
        """Maneja peticiones POST. Solo se acepta la ruta /auth."""
        if self.path == "/auth":
            self._handle_auth()
        else:
            # Cualquier otra ruta POST retorna 404
            self._send_json(404, {"status": "error", "message": "Ruta no encontrada"})

    def do_GET(self) -> None:
        """Maneja peticiones GET. Solo se acepta la ruta /health."""
        if self.path == "/health":
            # Endpoint de healthcheck para verificar que el servicio esta vivo
            self._send_json(200, {"status": "ok", "service": "auth"})
        else:
            self._send_json(404, {"status": "error", "message": "Ruta no encontrada"})

    def _handle_auth(self) -> None:
        """
        Procesa una solicitud de autenticacion.

        Flujo:
            1. Leer y parsear el cuerpo JSON de la peticion.
            2. Extraer 'username' y 'password'.
            3. Verificar contra la base de usuarios USERS.
            4. Retornar el resultado con el rol si es correcto, o 401 si no.
        """
        # Leer el Content-Length para saber cuantos bytes leer del cuerpo
        content_length = int(self.headers.get("Content-Length", 0))

        if content_length == 0:
            # No hay cuerpo: peticion malformada
            _log("WARN", f"POST /auth sin body desde {self.client_address}")
            self._send_json(400, {"status": "error", "message": "Body requerido"})
            return

        # Leer el cuerpo de la peticion
        raw_body = self.rfile.read(content_length)

        try:
            # Parsear el JSON
            body: dict = json.loads(raw_body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            _log("WARN", f"JSON invalido desde {self.client_address}: {exc}")
            self._send_json(400, {"status": "error", "message": "JSON invalido"})
            return

        # Extraer campos del body
        username: str = body.get("username", "").strip()
        password: str = body.get("password", "")

        if not username or not password:
            # Campos obligatorios faltantes
            _log("WARN", f"Auth sin username o password desde {self.client_address}")
            self._send_json(400, {"status": "error", "message": "username y password requeridos"})
            return

        # Buscar el usuario en la base de datos simulada
        user_record = USERS.get(username)

        if user_record is None or user_record["password"] != password:
            # Credenciales incorrectas: retornar 401 Unauthorized
            _log("INFO", f"Auth FALLIDA para usuario='{username}' desde {self.client_address}")
            self._send_json(401, {
                "status": "error",
                "message": "Credenciales invalidas",
            })
            return

        # Autenticacion exitosa: retornar datos del usuario (sin la contrasena)
        _log("INFO", f"Auth OK usuario='{username}' rol='{user_record['role']}' desde {self.client_address}")
        self._send_json(200, {
            "status": "ok",
            "username": username,
            "role": user_record["role"],
            "nombre": user_record["nombre"],
        })

    def _send_json(self, status_code: int, data: dict) -> None:
        """
        Envia una respuesta HTTP con cuerpo JSON.

        :param status_code: codigo de estado HTTP (200, 400, 401, 404...)
        :param data:        diccionario Python a serializar como JSON
        """
        # Serializar el diccionario a bytes UTF-8
        body: bytes = json.dumps(data, ensure_ascii=False).encode("utf-8")

        # Enviar la linea de estado y las cabeceras
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        # CORS permisivo para que el servidor web pueda consumir esta API
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        # Enviar el cuerpo
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        """
        Sobreescribe el log por defecto de BaseHTTPRequestHandler.
        Redirige al formato de log del sistema.
        """
        _log("HTTP", f"{self.address_string()} - {fmt % args}")


# ─── Funcion principal ────────────────────────────────────────────────────────

def run(host: str = "0.0.0.0", port: int = 8081) -> None:
    """
    Inicia el servidor HTTP del servicio de autenticacion.

    :param host: interfaz de red en la que escuchar (0.0.0.0 = todas)
    :param port: puerto en el que escuchar las peticiones HTTP
    """
    server = HTTPServer((host, port), AuthHandler)
    _log("INFO", f"Servicio de autenticacion iniciado en {host}:{port}")
    _log("INFO", f"Usuarios registrados: {', '.join(USERS.keys())}")

    try:
        # Bucle principal: atiende peticiones hasta recibir Ctrl+C
        server.serve_forever()
    except KeyboardInterrupt:
        _log("INFO", "Servicio de autenticacion detenido.")
    finally:
        server.server_close()


# ─── Punto de entrada ─────────────────────────────────────────────────────────

def _parse_args() -> argparse.Namespace:
    """Parsea los argumentos de linea de comandos."""
    parser = argparse.ArgumentParser(
        description="Servicio externo de autenticacion para el sistema IoT."
    )
    parser.add_argument(
        "--host",
        default=os.getenv("AUTH_HOST", "0.0.0.0"),
        help="Interfaz de red (defecto: 0.0.0.0)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("AUTH_PORT", "8081")),
        help="Puerto TCP del servicio (defecto: 8081)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    run(args.host, args.port)
