from __future__ import annotations

import socket
import threading
import time
import uuid
from collections import deque
from copy import deepcopy
from datetime import datetime
from typing import Callable


LogFn = Callable[[str, str], None]


def _now_text() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _format_number(value: float | None) -> str:
    if value is None:
        return ""
    return f"{value:.2f}"


def _alert_metadata(alert_code: str) -> dict[str, str]:
    mapping = {
        "temperatura_alta": {
            "severity": "critical",
            "label": "Temperatura alta",
        },
        "humedad_baja": {
            "severity": "warning",
            "label": "Humedad baja",
        },
        "vibracion_alta": {
            "severity": "critical",
            "label": "Vibracion alta",
        },
        "presion_anomala": {
            "severity": "warning",
            "label": "Presion anomala",
        },
        "consumo_alto": {
            "severity": "critical",
            "label": "Consumo alto",
        },
    }
    return mapping.get(
        alert_code,
        {
            "severity": "warning",
            "label": alert_code.replace("_", " ").capitalize(),
        },
    )


def _sensor_status(sensor_type: str, value: float | None) -> dict[str, str]:
    if value is None:
        return {
            "level": "idle",
            "label": "Sin medicion",
            "detail": "El sensor esta activo pero todavia no reporta una medicion reciente.",
        }

    if sensor_type == "temperatura" and value > 50.0:
        return {
            "level": "critical",
            "label": "Alerta",
            "detail": "Temperatura por encima del umbral definido por el servidor.",
        }
    if sensor_type == "humedad" and value < 20.0:
        return {
            "level": "warning",
            "label": "Atencion",
            "detail": "Humedad por debajo del rango esperado.",
        }
    if sensor_type == "vibracion" and value > 80.0:
        return {
            "level": "critical",
            "label": "Alerta",
            "detail": "Vibracion mecanica por encima del umbral.",
        }
    if sensor_type == "presion" and (value < 900.0 or value > 1100.0):
        return {
            "level": "warning",
            "label": "Atencion",
            "detail": "Presion fuera del rango normal del sistema.",
        }
    if sensor_type == "consumo" and value > 1000.0:
        return {
            "level": "critical",
            "label": "Alerta",
            "detail": "Consumo energetico alto.",
        }

    return {
        "level": "ok",
        "label": "Normal",
        "detail": "Ultima medicion dentro del rango esperado.",
    }


def _parse_sensors_response(response: str) -> list[dict]:
    sensors: list[dict] = []
    if not response.startswith("SENSORS "):
        raise ValueError(f"Respuesta inesperada para GET_SENSORS: '{response}'")

    for token in response.split()[2:]:
        if ":" not in token:
            continue
        sensor_id, sensor_type = token.split(":", 1)
        sensors.append({"id": sensor_id, "tipo": sensor_type})
    return sensors


def _parse_last_response(response: str) -> dict | None:
    if response.startswith("LAST "):
        parts = response.split(" ", 3)
        if len(parts) < 3:
            raise ValueError(f"Respuesta LAST invalida: '{response}'")
        try:
            value = float(parts[2])
        except ValueError as exc:
            raise ValueError(f"Valor invalido en LAST: '{response}'") from exc
        return {
            "id": parts[1],
            "valor": value,
            "valor_texto": _format_number(value),
            "timestamp": parts[3] if len(parts) == 4 else "",
        }

    if response.startswith("ERROR 404 NO_DATA") or response.startswith("ERROR 404 SENSOR_NOT_FOUND"):
        return None

    if response.startswith("ERROR "):
        raise ValueError(response)

    raise ValueError(f"Respuesta inesperada para GET_LAST: '{response}'")


def _parse_alert_line(line: str) -> dict:
    parts = line.split(" ", 4)
    if len(parts) < 5 or parts[0] != "ALERT":
        raise ValueError(f"Alerta invalida: '{line}'")

    try:
        numeric_value = float(parts[3])
    except ValueError:
        numeric_value = None

    meta = _alert_metadata(parts[2])
    return {
        "sensor_id": parts[1],
        "code": parts[2],
        "value": _format_number(numeric_value),
        "timestamp": parts[4],
        "severity": meta["severity"],
        "label": meta["label"],
        "received_at": _now_text(),
    }


class IotClient:
    def __init__(self, host: str, port: int, timeout: float = 5.0, log_fn: LogFn | None = None) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self._log = log_fn or (lambda _level, _message: None)

    def fetch_dashboard_sensors(self) -> list[dict]:
        operator_id = self._make_operator_id("webdash")
        sock, reader = self.open_operator_connection(operator_id)
        try:
            self._send_line(sock, "GET_SENSORS")
            sensors = _parse_sensors_response(self._read_line(reader))

            enriched: list[dict] = []
            for sensor in sensors:
                try:
                    last = self._read_last_for_sensor(sock, reader, sensor["id"])
                except Exception as exc:
                    self._log("WARN", f"No fue posible consultar GET_LAST para {sensor['id']}: {exc}")
                    last = None
                value = last["valor"] if last else None
                status = _sensor_status(sensor["tipo"], value)
                enriched.append(
                    {
                        "id": sensor["id"],
                        "tipo": sensor["tipo"],
                        "last_value": _format_number(value),
                        "last_value_number": value,
                        "last_timestamp": last["timestamp"] if last else "",
                        "status": status["level"],
                        "status_label": status["label"],
                        "status_detail": status["detail"],
                    }
                )

            enriched.sort(key=lambda item: item["id"])
            return enriched
        finally:
            self._close_connection(sock, reader)

    def open_operator_connection(self, operator_id: str | None = None):
        sock, reader = self._connect()
        op_id = operator_id or self._make_operator_id("webop")
        self._register_operator(sock, reader, op_id)
        return sock, reader

    def _connect(self):
        self._log("INFO", f"Resolviendo nombre del servidor IoT: {self.host}")
        addr_info = socket.getaddrinfo(
            self.host,
            self.port,
            family=socket.AF_UNSPEC,
            type=socket.SOCK_STREAM,
        )

        last_error: Exception | None = None
        for family, socktype, proto, _canonname, sockaddr in addr_info:
            sock = socket.socket(family, socktype, proto)
            try:
                sock.settimeout(self.timeout)
                sock.connect(sockaddr)
                self._log("INFO", f"Conexion TCP establecida con {sockaddr[0]}:{sockaddr[1]}")
                return sock, sock.makefile("r", encoding="utf-8", newline="\n")
            except OSError as exc:
                last_error = exc
                sock.close()

        raise OSError(f"No se pudo conectar a {self.host}:{self.port}: {last_error}")

    def _register_operator(self, sock, reader, operator_id: str) -> None:
        self._send_line(sock, f"REGISTER OPERATOR {operator_id}")
        expected = f"OK REGISTERED OPERATOR {operator_id}"
        response = self._read_line(reader)
        if response != expected:
            raise ValueError(f"Registro de operador fallido: '{response}'")

    def _read_last_for_sensor(self, sock, reader, sensor_id: str) -> dict | None:
        self._send_line(sock, f"GET_LAST {sensor_id}")
        response = self._read_line(reader)
        return _parse_last_response(response)

    def _send_line(self, sock, line: str) -> None:
        sock.sendall((line + "\n").encode("utf-8"))

    def _read_line(self, reader) -> str:
        response = reader.readline()
        if response == "":
            raise ConnectionError("El servidor IoT cerro la conexion")
        return response.strip()

    def _close_connection(self, sock, reader) -> None:
        try:
            reader.close()
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass

    @staticmethod
    def _make_operator_id(prefix: str) -> str:
        suffix = uuid.uuid4().hex[:8]
        return f"{prefix}_{suffix}"[:32]


class AlertFeed:
    def __init__(
        self,
        client: IotClient,
        *,
        ping_interval: float = 10.0,
        reconnect_delay: float = 3.0,
        max_alerts: int = 60,
        log_fn: LogFn | None = None,
    ) -> None:
        self._client = client
        self._ping_interval = ping_interval
        self._reconnect_delay = reconnect_delay
        self._alerts: deque[dict] = deque(maxlen=max_alerts)
        self._log = log_fn or (lambda _level, _message: None)
        self._state_lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._connected = False
        self._last_error = ""
        self._last_message_at = ""

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, name="web-alert-feed", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2)

    def clear(self) -> None:
        with self._state_lock:
            self._alerts.clear()

    def get_state(self) -> dict:
        with self._state_lock:
            return {
                "connected": self._connected,
                "last_error": self._last_error,
                "last_message_at": self._last_message_at,
                "alerts": list(self._alerts),
            }

    def _run(self) -> None:
        while not self._stop_event.is_set():
            ping_stop = threading.Event()
            sock = None
            reader = None
            ping_thread = None
            try:
                sock, reader = self._client.open_operator_connection()
                self._set_connection_state(connected=True, last_error="")
                ping_thread = threading.Thread(
                    target=self._ping_loop,
                    args=(sock, ping_stop),
                    name="web-alert-feed-pinger",
                    daemon=True,
                )
                ping_thread.start()

                while not self._stop_event.is_set():
                    line = self._client._read_line(reader)
                    if not line or line == "OK PONG":
                        continue
                    if line.startswith("ALERT "):
                        self._store_alert(_parse_alert_line(line))
                        continue
                    self._log("WARN", f"Linea inesperada desde el stream de alertas: {line}")
            except Exception as exc:
                self._set_connection_state(connected=False, last_error=str(exc))
                self._log("WARN", f"Fallo en el stream de alertas: {exc}")
                if self._stop_event.wait(self._reconnect_delay):
                    break
            finally:
                ping_stop.set()
                if ping_thread and ping_thread.is_alive():
                    ping_thread.join(timeout=1)
                if sock is not None and reader is not None:
                    self._client._close_connection(sock, reader)

    def _ping_loop(self, sock, ping_stop: threading.Event) -> None:
        while not ping_stop.wait(self._ping_interval):
            try:
                self._client._send_line(sock, "PING")
            except Exception as exc:
                self._log("WARN", f"No fue posible enviar PING al stream de alertas: {exc}")
                return

    def _store_alert(self, alert: dict) -> None:
        with self._state_lock:
            self._alerts.appendleft(alert)
            self._last_message_at = _now_text()

    def _set_connection_state(self, *, connected: bool, last_error: str) -> None:
        with self._state_lock:
            self._connected = connected
            self._last_error = last_error


class DashboardService:
    def __init__(
        self,
        client: IotClient,
        alert_feed: AlertFeed,
        *,
        refresh_interval: float = 5.0,
        max_measurements: int = 80,
        log_fn: LogFn | None = None,
    ) -> None:
        self._client = client
        self._alert_feed = alert_feed
        self._refresh_interval = refresh_interval
        self._measurements: deque[dict] = deque(maxlen=max_measurements)
        self._measurement_stamps: dict[str, str] = {}
        self._log = log_fn or (lambda _level, _message: None)
        self._data_lock = threading.Lock()
        self._refresh_lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._sensors: list[dict] = []
        self._last_snapshot_at = ""
        self._last_success_at = ""
        self._last_error = ""
        self._iot_reachable = False

    def start(self) -> None:
        self._alert_feed.start()
        self.refresh_now()
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._refresh_loop, name="web-dashboard-refresh", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2)
        self._alert_feed.stop()

    def refresh_now(self) -> dict:
        with self._refresh_lock:
            try:
                sensors = self._client.fetch_dashboard_sensors()
                self._record_measurements(sensors)
                with self._data_lock:
                    self._sensors = sensors
                    self._last_snapshot_at = _now_text()
                    self._last_success_at = self._last_snapshot_at
                    self._last_error = ""
                    self._iot_reachable = True
            except Exception as exc:
                self._log("WARN", f"No fue posible refrescar el dashboard: {exc}")
                with self._data_lock:
                    self._last_snapshot_at = _now_text()
                    self._last_error = str(exc)
                    self._iot_reachable = False

        return self.get_snapshot()

    def acknowledge_alerts(self) -> dict:
        self._alert_feed.clear()
        return self.get_snapshot()

    def get_snapshot(self) -> dict:
        with self._data_lock:
            sensors = deepcopy(self._sensors)
            measurements = list(self._measurements)
            last_snapshot_at = self._last_snapshot_at
            last_success_at = self._last_success_at
            last_error = self._last_error
            iot_reachable = self._iot_reachable

        alert_state = self._alert_feed.get_state()
        counts_by_type: dict[str, int] = {}
        critical_count = 0
        warning_count = 0
        idle_count = 0
        for sensor in sensors:
            counts_by_type[sensor["tipo"]] = counts_by_type.get(sensor["tipo"], 0) + 1
            if sensor["status"] == "critical":
                critical_count += 1
            elif sensor["status"] == "warning":
                warning_count += 1
            elif sensor["status"] == "idle":
                idle_count += 1

        stale = False
        if last_success_at:
            try:
                last_success = datetime.strptime(last_success_at, "%Y-%m-%d %H:%M:%S")
                stale = (datetime.now() - last_success).total_seconds() > (self._refresh_interval * 3)
            except ValueError:
                stale = not iot_reachable
        else:
            stale = True

        return {
            "updated_at": last_snapshot_at,
            "sensors": sensors,
            "alerts": alert_state["alerts"],
            "measurements": measurements,
            "metrics": {
                "sensor_count": len(sensors),
                "critical_count": critical_count,
                "warning_count": warning_count,
                "idle_count": idle_count,
                "measurement_count": len(measurements),
                "alert_count": len(alert_state["alerts"]),
                "by_type": counts_by_type,
            },
            "system": {
                "iot_host": self._client.host,
                "iot_port": self._client.port,
                "iot_reachable": iot_reachable,
                "alert_stream_connected": alert_state["connected"],
                "stale": stale,
                "last_error": last_error or alert_state["last_error"],
                "last_success_at": last_success_at,
                "last_alert_at": alert_state["last_message_at"],
                "dns_strategy": "socket.getaddrinfo",
                "supports_multi_clients": True,
                "auth_storage": "externo",
            },
            "actions": [
                {"id": "refresh", "label": "Actualizar ahora"},
                {"id": "acknowledge-alerts", "label": "Marcar alertas revisadas"},
            ],
        }

    def get_sensors(self) -> list[dict]:
        return self.get_snapshot()["sensors"]

    def get_alerts(self) -> list[dict]:
        return self.get_snapshot()["alerts"]

    def get_measurements(self) -> list[dict]:
        return self.get_snapshot()["measurements"]

    def _refresh_loop(self) -> None:
        while not self._stop_event.wait(self._refresh_interval):
            self.refresh_now()

    def _record_measurements(self, sensors: list[dict]) -> None:
        with self._data_lock:
            for sensor in sensors:
                timestamp = sensor["last_timestamp"]
                if not timestamp:
                    continue
                if self._measurement_stamps.get(sensor["id"]) == timestamp:
                    continue

                self._measurement_stamps[sensor["id"]] = timestamp
                self._measurements.appendleft(
                    {
                        "sensor_id": sensor["id"],
                        "tipo": sensor["tipo"],
                        "value": sensor["last_value"],
                        "timestamp": timestamp,
                        "status": sensor["status"],
                        "status_label": sensor["status_label"],
                    }
                )
