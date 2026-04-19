"""Base reutilizable para sensores IoT simulados."""

from __future__ import annotations

import abc
import random
import socket
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable, Optional


class SensorError(Exception):
    """Base class for sensor runtime errors."""


class ConnectionError(SensorError):
    """Raised when the sensor cannot connect or exchange data."""


class ProtocolError(SensorError):
    """Raised when the server replies with a protocol-level error."""

    def __init__(self, message: str, *, code: Optional[int] = None, recoverable: bool = True) -> None:
        super().__init__(message)
        self.code = code
        self.recoverable = recoverable


@dataclass(frozen=True)
class SensorConfig:
    host: str
    port: int
    sensor_id: str
    sensor_type: str
    interval: float
    reconnect_delay: float = 5.0
    heartbeat_interval: float = 10.0
    connect_timeout: float = 5.0
    io_timeout: float = 10.0

    def __post_init__(self) -> None:
        if not self.host:
            raise ValueError("host no puede estar vacio")
        if self.port <= 0:
            raise ValueError("port debe ser mayor que 0")
        if self.interval <= 0:
            raise ValueError("interval debe ser mayor que 0")
        if self.reconnect_delay <= 0:
            raise ValueError("reconnect_delay debe ser mayor que 0")
        if self.heartbeat_interval <= 0:
            raise ValueError("heartbeat_interval debe ser mayor que 0")
        if self.connect_timeout <= 0:
            raise ValueError("connect_timeout debe ser mayor que 0")
        if self.io_timeout <= 0:
            raise ValueError("io_timeout debe ser mayor que 0")


class BaseSensor(abc.ABC):
    """Sensor base con reconexion automatica y envio periodico."""

    def __init__(self, config: SensorConfig, *, stop_event: Optional[threading.Event] = None) -> None:
        self.config = config
        self.stop_event = stop_event or threading.Event()
        self._socket: Optional[socket.socket] = None
        self._rng = random.Random(f"{config.sensor_id}:{config.sensor_type}")

    @abc.abstractmethod
    def generate_measurement(self) -> float:
        """Return the next simulated measurement."""

    def run_forever(self) -> None:
        self._log("INFO", "Iniciando sensor")

        while not self.stop_event.is_set():
            try:
                self._connect_and_register()
                self._publish_loop()
            except ProtocolError as exc:
                if self.stop_event.is_set():
                    break
                level = "ERROR" if not exc.recoverable else "WARN"
                self._log(level, f"Error de protocolo: {exc}")
                if not exc.recoverable:
                    break
            except (ConnectionError, OSError) as exc:
                if self.stop_event.is_set():
                    break
                self._log("WARN", f"Fallo de conexion: {exc}")
            finally:
                self._close_socket()

            if self.stop_event.is_set():
                break

            self._log("INFO", f"Reintentando en {self.config.reconnect_delay:.1f}s")
            self.stop_event.wait(self.config.reconnect_delay)

        self._close_socket()
        self._log("INFO", "Sensor detenido")

    def stop(self) -> None:
        self.stop_event.set()
        self._close_socket()

    def _connect_and_register(self) -> None:
        addresses = self._resolve_addresses()
        self._socket = self._open_socket(addresses)
        self._register_sensor()

    def _resolve_addresses(self) -> list[tuple]:
        try:
            info = socket.getaddrinfo(
                self.config.host,
                self.config.port,
                family=socket.AF_UNSPEC,
                type=socket.SOCK_STREAM,
            )
        except socket.gaierror as exc:
            raise ConnectionError(
                f"No se pudo resolver el host '{self.config.host}': {exc}"
            ) from exc

        endpoints = [self._format_sockaddr(item[4]) for item in info]
        self._log(
            "INFO",
            f"Host resuelto '{self.config.host}' -> {', '.join(endpoints)}",
        )
        return info

    def _open_socket(self, addresses: Iterable[tuple]) -> socket.socket:
        last_error: Optional[Exception] = None

        for family, socktype, proto, _canonname, sockaddr in addresses:
            sock = socket.socket(family, socktype, proto)
            try:
                sock.settimeout(self.config.connect_timeout)
                sock.connect(sockaddr)
                sock.settimeout(self.config.io_timeout)
                self._log("INFO", f"Conexion TCP establecida con {self._format_sockaddr(sockaddr)}")
                return sock
            except OSError as exc:
                last_error = exc
                sock.close()

        raise ConnectionError(f"No se pudo conectar al servidor: {last_error}")

    def _register_sensor(self) -> None:
        expected = f"OK REGISTERED SENSOR {self.config.sensor_id}"
        response = self._exchange(
            f"REGISTER SENSOR {self.config.sensor_id} {self.config.sensor_type}"
        )
        self._expect_exact(response, expected, action="registro")
        self._log("INFO", "Registro completado")

    def _publish_loop(self) -> None:
        next_data_at = time.monotonic()
        last_activity_at = time.monotonic()

        while not self.stop_event.is_set():
            now = time.monotonic()

            if now >= next_data_at:
                value = self.generate_measurement()
                self._send_measurement(value)
                sent_at = time.monotonic()
                last_activity_at = sent_at
                next_data_at = sent_at + self.config.interval
                continue

            if now - last_activity_at >= self.config.heartbeat_interval:
                self._send_ping()
                last_activity_at = time.monotonic()
                continue

            wait_seconds = min(
                max(next_data_at - now, 0.0),
                max(self.config.heartbeat_interval - (now - last_activity_at), 0.0),
                0.5,
            )
            self.stop_event.wait(wait_seconds if wait_seconds > 0 else 0.1)

    def _send_measurement(self, value: float) -> None:
        payload = f"DATA {self.config.sensor_id} {value:.2f}"
        response = self._exchange(payload)
        expected = f"OK DATA_RECEIVED {self.config.sensor_id}"
        self._expect_exact(response, expected, action="envio de datos")
        self._log("INFO", f"Medicion enviada: {value:.2f}")

    def _send_ping(self) -> None:
        response = self._exchange("PING")
        self._expect_exact(response, "OK PONG", action="heartbeat")
        self._log("INFO", "Heartbeat OK")

    def _exchange(self, line: str) -> str:
        if self._socket is None:
            raise ConnectionError("No hay socket activo")

        data = f"{line}\n".encode("utf-8")
        try:
            self._socket.sendall(data)
            response = self._recv_line()
        except OSError as exc:
            raise ConnectionError(f"Error intercambiando datos con el servidor: {exc}") from exc

        self._log("DEBUG", f"TX='{line}' RX='{response}'")
        self._raise_if_protocol_error(response)
        return response

    def _recv_line(self) -> str:
        if self._socket is None:
            raise ConnectionError("No hay socket activo")

        chunks: list[bytes] = []
        received_bytes = 0
        while True:
            chunk = self._socket.recv(1)
            if not chunk:
                raise ConnectionError("El servidor cerro la conexion")
            if chunk == b"\n":
                break
            if chunk != b"\r":
                chunks.append(chunk)
                received_bytes += len(chunk)
            if received_bytes > 4096:
                raise ProtocolError("Linea de respuesta demasiado larga", recoverable=True)

        return b"".join(chunks).decode("utf-8", errors="replace").strip()

    def _raise_if_protocol_error(self, response: str) -> None:
        if not response.startswith("ERROR "):
            return

        parts = response.split(" ", 2)
        code = None
        reason = "UNKNOWN"
        if len(parts) >= 2 and parts[1].isdigit():
            code = int(parts[1])
        if len(parts) == 3:
            reason = parts[2]

        recoverable = self._is_recoverable_protocol_error(code, reason)
        raise ProtocolError(
            f"Servidor respondio '{response}'",
            code=code,
            recoverable=recoverable,
        )

    def _is_recoverable_protocol_error(self, code: Optional[int], reason: str) -> bool:
        fatal_pairs = {
            (400, "INVALID_ID"),
            (409, "SENSOR_TYPE_MISMATCH"),
            (422, "INVALID_SENSOR_TYPE"),
        }
        if (code, reason) in fatal_pairs:
            return False
        return True

    def _expect_exact(self, response: str, expected: str, *, action: str) -> None:
        if response != expected:
            raise ProtocolError(
                f"Respuesta inesperada en {action}: se esperaba '{expected}' y llego '{response}'",
                recoverable=True,
            )

    def _close_socket(self) -> None:
        if self._socket is None:
            return
        try:
            self._socket.close()
        finally:
            self._socket = None

    def _log(self, level: str, message: str) -> None:
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(
            f"[{timestamp}] [{level}] [{self.config.sensor_type}:{self.config.sensor_id}] {message}",
            flush=True,
        )

    @staticmethod
    def _format_sockaddr(sockaddr: tuple) -> str:
        if len(sockaddr) >= 2:
            return f"{sockaddr[0]}:{sockaddr[1]}"
        return str(sockaddr)
