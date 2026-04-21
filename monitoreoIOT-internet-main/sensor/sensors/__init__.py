"""Sensores concretos y fabrica de sensores."""

from __future__ import annotations

import threading
from typing import Type

from sensor.base_sensor import BaseSensor, SensorConfig
from sensor.sensors.humidity_sensor import HumiditySensor
from sensor.sensors.power_sensor import PowerSensor
from sensor.sensors.pressure_sensor import PressureSensor
from sensor.sensors.temperature_sensor import TemperatureSensor
from sensor.sensors.vibration_sensor import VibrationSensor


SENSOR_TYPES: dict[str, Type[BaseSensor]] = {
    "temperatura": TemperatureSensor,
    "humedad": HumiditySensor,
    "vibracion": VibrationSensor,
    "presion": PressureSensor,
    "consumo": PowerSensor,
}


def create_sensor(
    config: SensorConfig,
    *,
    stop_event: threading.Event | None = None,
) -> BaseSensor:
    try:
        sensor_class = SENSOR_TYPES[config.sensor_type]
    except KeyError as exc:
        raise ValueError(f"Tipo de sensor no soportado: {config.sensor_type}") from exc
    return sensor_class(config, stop_event=stop_event)

