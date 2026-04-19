# Sensores Simulados IoT

Esta carpeta implementa la parte de sensores simulados en Python usando el protocolo textual definido en `docs/02-protocolo/especificacion-protocolo.md`.

## Archivos

- `base_sensor.py`: logica comun de conexion TCP, registro, heartbeat, envio periodico y reconexion.
- `run_sensor.py`: ejecuta un sensor individual.
- `run_many.py`: levanta los cinco sensores del proyecto en un solo proceso.
- `sensors/`: clases concretas para temperatura, humedad, vibracion, presion y consumo.

## Protocolo usado

Los sensores usan exactamente estos mensajes:

- `REGISTER SENSOR <sensor_id> <sensor_type>`
- `DATA <sensor_id> <valor>`
- `PING`

Respuestas esperadas:

- `OK REGISTERED SENSOR <sensor_id>`
- `OK DATA_RECEIVED <sensor_id>`
- `OK PONG`
- `ERROR <codigo> <detalle>`

## Ejecucion

Desde la raiz del repositorio:

```bash
python -m sensor.run_sensor temperatura --host localhost --port 9000 --sensor-id temp_01 --interval 4
```

Para levantar los cinco sensores:

```bash
python -m sensor.run_many --host localhost --port 9000
```

## Variables de entorno soportadas

- `IOT_SERVER_HOST`
- `IOT_SERVER_PORT`
- `IOT_SENSOR_TYPE`
- `IOT_SENSOR_ID`
- `IOT_SENSOR_INTERVAL`
- `IOT_SENSOR_RECONNECT_DELAY`
- `IOT_SENSOR_HEARTBEAT_INTERVAL`
- `IOT_SENSOR_CONNECT_TIMEOUT`
- `IOT_SENSOR_IO_TIMEOUT`

## Sensores incluidos

- `temperatura`
- `humedad`
- `vibracion`
- `presion`
- `consumo`

Cada sensor resuelve el hostname con `socket.getaddrinfo`, se registra al conectar y reintenta automaticamente si el socket se cae o el servidor deja de responder.

