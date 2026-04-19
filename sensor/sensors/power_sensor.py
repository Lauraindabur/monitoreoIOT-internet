"""Sensor simulado de consumo energetico."""

from __future__ import annotations

from sensor.base_sensor import BaseSensor, SensorConfig


class PowerSensor(BaseSensor):
    def __init__(self, config: SensorConfig, **kwargs) -> None:
        super().__init__(config, **kwargs)
        self._value = self._rng.uniform(250.0, 500.0)

    def generate_measurement(self) -> float:
        self._value += self._rng.uniform(-45.0, 60.0)
        self._value = min(max(self._value, 120.0), 900.0)

        # Sube a veces a un valor alto para validar consumo_alto.
        if self._rng.random() < 0.07:
            self._value = self._rng.uniform(1020.0, 1230.0)

        return round(self._value, 2)
