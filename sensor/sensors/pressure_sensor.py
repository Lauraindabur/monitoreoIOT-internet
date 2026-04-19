"""Sensor simulado de presion."""

from __future__ import annotations

from sensor.base_sensor import BaseSensor, SensorConfig


class PressureSensor(BaseSensor):
    def __init__(self, config: SensorConfig, **kwargs) -> None:
        super().__init__(config, **kwargs)
        self._value = self._rng.uniform(985.0, 1015.0)

    def generate_measurement(self) -> float:
        self._value += self._rng.uniform(-4.5, 4.5)
        self._value = min(max(self._value, 950.0), 1050.0)

        # Se desvía a veces para gatillar presion anomala.
        if self._rng.random() < 0.05:
            self._value = self._rng.choice(
                [self._rng.uniform(870.0, 895.0), self._rng.uniform(1105.0, 1125.0)]
            )

        return round(self._value, 2)

