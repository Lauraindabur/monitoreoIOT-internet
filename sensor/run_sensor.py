"""Runner para ejecutar un sensor simulado."""

from __future__ import annotations

import argparse
import os
import sys
import uuid

if __package__ in {None, ""}:
    sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from sensor.base_sensor import SensorConfig
from sensor.sensors import SENSOR_TYPES, create_sensor


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    return float(value) if value is not None else default


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    return int(value) if value is not None else default


def _default_sensor_id(sensor_type: str) -> str:
    return f"{sensor_type}_{uuid.uuid4().hex[:6]}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Ejecuta un sensor IoT simulado compatible con el protocolo del proyecto.",
    )
    parser.add_argument(
        "sensor_type",
        nargs="?",
        choices=sorted(SENSOR_TYPES),
        default=os.getenv("IOT_SENSOR_TYPE"),
        help="Tipo del sensor a ejecutar.",
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
        "--sensor-id",
        default=os.getenv("IOT_SENSOR_ID"),
        help="ID unico del sensor. Si no se indica, se genera uno automaticamente.",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=_env_float("IOT_SENSOR_INTERVAL", 5.0),
        help="Segundos entre mediciones.",
    )
    parser.add_argument(
        "--reconnect-delay",
        type=float,
        default=_env_float("IOT_SENSOR_RECONNECT_DELAY", 5.0),
        help="Segundos de espera antes de reintentar conexion.",
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
    parser = build_parser()
    args = parser.parse_args()

    if not args.sensor_type:
        parser.error("Debe indicar sensor_type o definir IOT_SENSOR_TYPE.")

    sensor_id = args.sensor_id or _default_sensor_id(args.sensor_type)
    config = SensorConfig(
        host=args.host,
        port=args.port,
        sensor_id=sensor_id,
        sensor_type=args.sensor_type,
        interval=args.interval,
        reconnect_delay=args.reconnect_delay,
        heartbeat_interval=args.heartbeat_interval,
        connect_timeout=args.connect_timeout,
        io_timeout=args.io_timeout,
    )

    sensor = create_sensor(config)
    try:
        sensor.run_forever()
    except KeyboardInterrupt:
        sensor.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

