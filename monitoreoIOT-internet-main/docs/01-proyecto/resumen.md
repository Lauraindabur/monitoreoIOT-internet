# Resumen del Proyecto

Este proyecto implementa un sistema distribuido de monitoreo IoT con un servidor central (TCP), sensores simulados y dos interfaces de supervision: un cliente de escritorio (Java) y un dashboard web (Python). La comunicacion entre los componentes IoT se hace con un protocolo de texto (line-based) sobre sockets TCP.

## Objetivo

- Registrar sensores de distintos tipos y recibir sus datos de telemetria.
- Mantener un inventario de sensores activos y su ultima medicion.
- Notificar alertas a los operadores en tiempo real (push).
- Exponer supervision por GUI (Operador Java) y por web (Dashboard).
- Separar autenticacion de usuarios del servidor IoT principal (capa web con servicio externo).

## Componentes del sistema

### 1) Servidor IoT (C++)

Ubicacion: `server/`  
Responsabilidad: concentrar las conexiones TCP, registrar sensores/operadores, persistir el ultimo valor por sensor y emitir alertas a operadores.

Protocolo implementado (fase 1):

- `PING`
- `REGISTER SENSOR <sensor_id> <tipo>`
- `REGISTER OPERATOR <operator_id>`
- `DATA <sensor_id> <valor>`
- `GET_SENSORS`
- `GET_LAST <sensor_id>`

Respuestas y eventos:

- `OK ...`
- `ERROR <codigo> <detalle>`
- `SENSORS ...`
- `LAST ...`
- `ALERT ...` (push a operadores)

### 2) Sensores simulados (Python)

Ubicacion: `sensor/`  
Responsabilidad: simular sensores (temperatura, humedad, vibracion, presion, consumo) que se conectan por TCP, se registran y envian datos periodicos.

Incluye:

- `base_sensor.py`: conexion, registro, heartbeat, reconexion y envio.
- `run_sensor.py`: ejecuta un sensor individual.
- `run_many.py`: levanta los cinco sensores en un solo proceso.
- `sensors/`: implementaciones por tipo.

Mensajes usados por los sensores:

- `REGISTER SENSOR <sensor_id> <sensor_type>`
- `DATA <sensor_id> <valor>`
- `PING`

### 3) Operador (Java Swing)

Ubicacion: `operador/`  
Responsabilidad: supervision de escritorio. Se conecta por TCP como operador, consulta sensores y mediciones, recibe `ALERT ...` en tiempo real y mantiene supervision automatica local.

Archivo principal: `OperadorClient.java`

Funciones clave:

- Conecta por DNS (no hay IPs hardcodeadas), registra `REGISTER OPERATOR <id>`.
- `GET_SENSORS` para poblar la tabla.
- `GET_LAST <sensor_id>` para mediciones y detalle.
- Heartbeat con `PING` (espera `OK PONG`).
- Separacion de respuestas normales vs. eventos `ALERT` en hilos de fondo.
- Supervision local: modo por sensor o global, ejecutando ciclos periodicos de consulta.

Nota: el cliente Java no gestiona usuarios ni contrasenas.

### 4) Capa web (Python, biblioteca estandar)

Ubicacion: `web/`  
Responsabilidad: dashboard web con sesion por cookie y API JSON para el frontend; consume el servidor IoT por TCP y mantiene un stream de alertas en segundo plano.

Servicios:

- `auth_service.py` (HTTP): servicio externo de autenticacion (usuarios y roles) en `:8081`.
- `web_server.py` (HTTP): servidor del dashboard en `:8080`.

Puntos clave:

- Login en `/login` y cookie `session_id` (HttpOnly).
- API (requiere sesion) para snapshot, sensores, mediciones y alertas.
- API para ejecutar comandos permitidos contra IoT: `PING`, `GET_SENSORS`, `GET_LAST` (via `/api/commands`).
- `dashboard_service.py` mantiene un snapshot y un feed de alertas con reconexion.
- Resolucion DNS al conectar al IoT via `socket.getaddrinfo` (sin IPs hardcodeadas).

## Arquitectura (alto nivel)

1. Sensores (Python) se conectan al Servidor IoT (C++) por TCP y envian `DATA`.
2. Operadores se conectan al Servidor IoT por TCP:
   - Cliente Java: supervision de escritorio y recepcion de `ALERT`.
   - Capa web: el servidor web consulta sensores/mediciones y mantiene un stream de alertas.
3. La web autentica usuarios contra un servicio externo (`auth_service.py`); el servidor IoT principal no almacena usuarios.

## Como ejecutar el proyecto (end-to-end)

En modo local tipico:

1. Compilar y ejecutar el servidor IoT (C++):
   - Ver `server/README.md`.
2. Levantar sensores simulados (Python):
   - Un sensor: `python -m sensor.run_sensor ...`
   - Todos: `python -m sensor.run_many --host localhost --port 9000`
3. Elegir interfaz de supervision:
   - Operador Java: compilar/ejecutar `operador/OperadorClient.java`.
   - Web: ejecutar `web/auth_service.py` y `web/web_server.py` y abrir `http://localhost:8080`.

## Alcance y limitaciones (intencionales)

- La supervision remota se basa en comandos de consulta del protocolo (`GET_SENSORS`, `GET_LAST`, `PING`); no hay comandos de control de actuadores.
- La autenticacion esta desacoplada y vive en la capa web (servicio externo). El cliente Java no consume ese servicio.
