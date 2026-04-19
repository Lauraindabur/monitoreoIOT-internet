"""Sensor simulado de humedad."""

from __future__ import annotations

from sensor.base_sensor import BaseSensor, SensorConfig


class HumiditySensor(BaseSensor):
    def __init__(self, config: SensorConfig, **kwargs) -> None:
        super().__init__(config, **kwargs)
        self._value = self._rng.uniform(40.0, 65.0)

    def generate_measurement(self) -> float:
        self._value += self._rng.uniform(-3.0, 3.0)
        self._value = min(max(self._value, 25.0), 85.0)

        # Baja esporadicamente para cubrir la alerta de humedad baja.
        if self._rng.random() < 0.06:
            self._value = self._rng.uniform(10.0, 18.5)

        return round(self._value, 2)

