"""Runner para levantar los cinco sensores simulados en un solo proceso."""

from __future__ import annotations

import argparse
import os
import sys
import threading

if __package__ in {None, ""}:
    sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from sensor.base_sensor import SensorConfig
from sensor.sensors import create_sensor


DEFAULT_SENSORS = [
    ("temp_01", "temperatura", 4.0),
    ("hum_01", "humedad", 5.0),
    ("vib_01", "vibracion", 3.0),
    ("pres_01", "presion", 6.0),
    ("cons_01", "consumo", 7.0),
]


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    return float(value) if value is not None else default


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    return int(value) if value is not None else default


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Levanta los cinco sensores IoT simulados del proyecto.",
    )
    parser.add_argument(
        "--host",
        default=os.getenv("IOT_SERVER_HOST", "localhost"),
        help="Hostname del servidor TCP.",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=_env_int("IOT_SERVER_PORT", 9000),
        help="Puerto TCP del servidor.",
    )
    parser.add_argument(
        "--reconnect-delay",
        type=float,
        default=_env_float("IOT_SENSOR_RECONNECT_DELAY", 5.0),
        help="Segundos antes de reintentar conexion.",
    )
    parser.add_argument(
        "--heartbeat-interval",
        type=float,
        default=_env_float("IOT_SENSOR_HEARTBEAT_INTERVAL", 10.0),
        help="Segundos maximos sin trafico antes de enviar PING.",
    )
    parser.add_argument(
        "--connect-timeout",
        type=float,
        default=_env_float("IOT_SENSOR_CONNECT_TIMEOUT", 5.0),
        help="Timeout de conexion TCP en segundos.",
    )
    parser.add_argument(
        "--io-timeout",
        type=float,
        default=_env_float("IOT_SENSOR_IO_TIMEOUT", 10.0),
        help="Timeout de lectura/escritura en segundos.",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    stop_event = threading.Event()
    threads: list[threading.Thread] = []
    sensors = []

    for sensor_id, sensor_type, interval in DEFAULT_SENSORS:
        config = SensorConfig(
            host=args.host,
            port=args.port,
            sensor_id=sensor_id,
            sensor_type=sensor_type,
            interval=interval,
            reconnect_delay=args.reconnect_delay,
            heartbeat_interval=args.heartbeat_interval,
            connect_timeout=args.connect_timeout,
            io_timeout=args.io_timeout,
        )
        sensor = create_sensor(config, stop_event=stop_event)
        thread = threading.Thread(
            target=sensor.run_forever,
            name=f"{sensor_type}-{sensor_id}",
        )
        sensors.append(sensor)
        threads.append(thread)

    for thread in threads:
        thread.start()

    try:
        for thread in threads:
            thread.join()
    except KeyboardInterrupt:
        stop_event.set()
        for sensor in sensors:
            sensor.stop()
        for thread in threads:
            thread.join()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

