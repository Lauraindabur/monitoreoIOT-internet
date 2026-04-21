# Sensores (simulados) — conexión y envío de datos

Este proyecto incluye **5 sensores simulados** que generan mediciones y las envían a un **servidor TCP** usando el protocolo textual definido en `docs/02-protocolo/especificacion-protocolo.md`.

## Dónde está implementado

- Implementación (Python): `sensor/`
  - Base reusable: `sensor/base_sensor.py`
  - Sensores concretos: `sensor/sensors/`
  - Ejecución: `sensor/run_sensor.py` (uno) y `sensor/run_many.py` (los cinco)

> Nota: en este repositorio, el componente en **Java** corresponde al operador (`operador/OperadorClient.java`); la simulación de sensores está implementada en **Python**.

## Sensores incluidos (mínimo 5)

Los cinco tipos de sensores simulados viven en `sensor/sensors/` y se instancian vía la fábrica `sensor/sensors/__init__.py`:

- `temperatura` (TemperatureSensor)
- `humedad` (HumiditySensor)
- `vibracion` (VibrationSensor)
- `presion` (PressureSensor)
- `consumo` (PowerSensor)

Cada clase concreta implementa `generate_measurement()` para producir valores simulados (con variación y, en algunos casos, picos ocasionales) que sirven para probar procesamiento y alertas aguas arriba.

## Conexión (TCP) y registro

La lógica común de **conexión** y **registro** está centralizada en `sensor/base_sensor.py`:

1. Resolución de hostname con `socket.getaddrinfo(host, port)` para soportar IPv4/IPv6.
2. Intento de conexión TCP (`socket.connect`) con `connect_timeout`.
3. Configuración de `io_timeout` para lecturas/escrituras.
4. Registro del sensor apenas conecta:
   - TX: `REGISTER SENSOR <sensor_id> <sensor_type>`
   - RX esperado: `OK REGISTERED SENSOR <sensor_id>`

Si ocurre un fallo de red o el servidor cierra la conexión, el sensor cierra el socket y **reintenta** tras `reconnect_delay`.

## Envío de datos (DATA) y heartbeat (PING)

Una vez registrado, el sensor entra a un loop de publicación:

- **Envío periódico de mediciones** cada `interval` segundos:
  - TX: `DATA <sensor_id> <valor>`
  - RX esperado: `OK DATA_RECEIVED <sensor_id>`
- **Heartbeat** cuando no hay tráfico por `heartbeat_interval` segundos:
  - TX: `PING`
  - RX esperado: `OK PONG`

El intercambio se hace línea a línea (terminadas en `\n`) y cualquier respuesta `ERROR ...` se interpreta como error de protocolo. Ciertos errores se tratan como **fatales** (por ejemplo: ID inválido o tipo de sensor inválido) y detienen el sensor.

## Ejecución

- Ejecutar un sensor:
  - `python -m sensor.run_sensor temperatura --host localhost --port 9000 --sensor-id temp_01 --interval 4`
- Ejecutar los 5 sensores en un solo proceso (con hilos):
  - `python -m sensor.run_many --host localhost --port 9000`

Ambos runners soportan configuración por argumentos y/o variables de entorno (por ejemplo: `IOT_SERVER_HOST`, `IOT_SERVER_PORT`, `IOT_SENSOR_INTERVAL`, `IOT_SENSOR_HEARTBEAT_INTERVAL`, `IOT_SENSOR_RECONNECT_DELAY`).

