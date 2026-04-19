"""Sensor simulado de vibracion."""

from __future__ import annotations

from sensor.base_sensor import BaseSensor, SensorConfig


class VibrationSensor(BaseSensor):
    def __init__(self, config: SensorConfig, **kwargs) -> None:
        super().__init__(config, **kwargs)
        self._value = self._rng.uniform(8.0, 20.0)

    def generate_measurement(self) -> float:
        self._value += self._rng.uniform(-4.0, 4.0)
        self._value = min(max(self._value, 2.0), 40.0)

        # Un spike ocasional permite observar vibracion alta.
        if self._rng.random() < 0.08:
            self._value = self._rng.uniform(82.0, 96.0)

        return round(self._value, 2)

