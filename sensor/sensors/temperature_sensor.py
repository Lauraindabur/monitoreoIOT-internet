"""Sensor simulado de temperatura."""

from __future__ import annotations

from sensor.base_sensor import BaseSensor, SensorConfig


class TemperatureSensor(BaseSensor):
    def __init__(self, config: SensorConfig, **kwargs) -> None:
        super().__init__(config, **kwargs)
        self._value = self._rng.uniform(21.0, 28.0)

    def generate_measurement(self) -> float:
        self._value += self._rng.uniform(-0.8, 0.8)
        self._value = min(max(self._value, 16.0), 38.0)

        # Un pico ocasional ayuda a probar alertas de temperatura alta.
        if self._rng.random() < 0.05:
            self._value = self._rng.uniform(51.0, 57.0)

        return round(self._value, 2)

