# Especificacion Formal del Protocolo de Aplicacion

## 1. Alcance
Este documento define el protocolo de aplicacion basado en texto para la comunicacion entre:
- Sensores IoT
- Operadores
- Servidor central de monitoreo

El protocolo opera sobre TCP (SOCK_STREAM) y cada mensaje se transmite en una linea terminada en salto de linea (\n).

## 2. Version
- Nombre: IOT-MONITOR-TEXT
- Version: 1.0
- Transporte: TCP
- Codificacion: UTF-8 (mensajes ASCII recomendados)

## 3. Reglas generales de formato
- Un mensaje = una linea.
- Separador de campos: espacio simple.
- No se permiten tabulaciones.
- Longitud maxima por linea: 4096 bytes.
- Comandos y tokens son sensibles a mayusculas/minusculas.

## 4. Gramatica base (EBNF simplificada)

```ebnf
message        = request | response ;
request        = ping | register_sensor | register_operator | data | get_sensors | get_last ;

ping           = "PING" ;
register_sensor   = "REGISTER" SP "SENSOR" SP sensor_id SP sensor_type ;
register_operator = "REGISTER" SP "OPERATOR" SP operator_id ;
data           = "DATA" SP sensor_id SP number ;
get_sensors    = "GET_SENSORS" ;
get_last       = "GET_LAST" SP sensor_id ;

response       = ok | error | sensors | last | alert ;
ok             = "OK" SP text ;
error          = "ERROR" SP code SP reason ;
sensors        = "SENSORS" SP integer *(SP sensor_pair) ;
sensor_pair    = sensor_id ":" sensor_type ;
last           = "LAST" SP sensor_id SP number SP timestamp ;
alert          = "ALERT" SP sensor_id SP alert_type SP number SP timestamp ;

SP             = " " ;
```

## 5. Tipos de dato y validaciones

### 5.1 sensor_id y operator_id
- Regex: ^[a-zA-Z0-9_-]{3,32}$
- Deben ser unicos por entidad.

### 5.2 sensor_type permitido
- temperatura
- humedad
- vibracion
- presion
- consumo

### 5.3 valor numerico
- Formato: decimal con punto.
- Rango general aceptado: -100000.0 a 100000.0
- Si no cumple formato o rango: ERROR 400 BAD_VALUE

### 5.4 validacion de aridad
- Cantidad exacta de parametros por comando.
- Si sobran o faltan parametros: ERROR 400 BAD_REQUEST

## 6. Maquina de estados de conexion

### 6.1 Estados
- CONNECTED_UNREGISTERED
- REGISTERED_SENSOR
- REGISTERED_OPERATOR
- DISCONNECTED

### 6.2 Reglas
- Un cliente inicia en CONNECTED_UNREGISTERED.
- Solo se permite un REGISTER por conexion.
- DATA solo permitido en REGISTERED_SENSOR.
- GET_SENSORS y GET_LAST permitidos en cualquier estado registrado.
- Al cerrar socket o timeout, el cliente pasa a DISCONNECTED.

## 7. Heartbeat y timeout
- Comando de heartbeat: PING
- Respuesta del servidor: OK PONG
- Timeout de inactividad implementado: 30 segundos
- Si el cliente supera timeout, el servidor debe cerrar la conexion y registrar evento de timeout.

## 8. Comandos y respuestas

### 8.1 PING
Request:
- PING
Response:
- OK PONG

### 8.2 REGISTER SENSOR
Request:
- REGISTER SENSOR <sensor_id> <sensor_type>
Response exitosa:
- OK REGISTERED SENSOR <sensor_id>
Errores:
- ERROR 400 BAD_REQUEST
- ERROR 409 ALREADY_EXISTS
- ERROR 409 ALREADY_REGISTERED
- ERROR 409 SENSOR_TYPE_MISMATCH
- ERROR 422 INVALID_SENSOR_TYPE

### 8.3 REGISTER OPERATOR
Request:
- REGISTER OPERATOR <operator_id>
Response exitosa:
- OK REGISTERED OPERATOR <operator_id>
Errores:
- ERROR 400 BAD_REQUEST
- ERROR 409 ALREADY_EXISTS
- ERROR 409 ALREADY_REGISTERED

### 8.4 DATA
Request:
- DATA <sensor_id> <valor>
Response exitosa:
- OK DATA_RECEIVED <sensor_id>
Errores:
- ERROR 400 BAD_REQUEST
- ERROR 400 BAD_VALUE
- ERROR 400 INVALID_ID
- ERROR 403 NOT_REGISTERED
- ERROR 403 FORBIDDEN_SENSOR_ID
- ERROR 404 SENSOR_NOT_FOUND

### 8.5 GET_SENSORS
Request:
- GET_SENSORS
Response:
- SENSORS <cantidad> <sensor_id1>:<tipo1> <sensor_id2>:<tipo2> ...

### 8.6 GET_LAST
Request:
- GET_LAST <sensor_id>
Response:
- LAST <sensor_id> <valor> <timestamp>
Errores:
- ERROR 400 BAD_REQUEST
- ERROR 404 SENSOR_NOT_FOUND
- ERROR 404 NO_DATA

### 8.7 ALERT (push servidor -> operador)
Formato:
- ALERT <sensor_id> <alert_type> <valor> <timestamp>

## 9. Reglas de alerta por tipo de sensor
- temperatura: valor > 50.0 -> temperatura_alta
- humedad: valor < 20.0 -> humedad_baja
- vibracion: valor > 80.0 -> vibracion_alta
- presion: valor fuera de [900, 1100] -> presion_anomala
- consumo: valor > 1000.0 -> consumo_alto

## 10. Tabla de codigos de error
- 400 BAD_REQUEST: formato invalido, aridad invalida o comando desconocido
- 400 BAD_VALUE: valor numerico invalido
- 400 INVALID_ID: identificador con formato invalido
- 403 NOT_REGISTERED: entidad no registrada para la operacion
- 403 FORBIDDEN_SENSOR_ID: un sensor intenta publicar con ID distinto al registrado
- 404 SENSOR_NOT_FOUND: sensor inexistente
- 404 NO_DATA: no hay mediciones para el sensor
- 409 ALREADY_EXISTS: entidad ya registrada
- 409 ALREADY_REGISTERED: la conexion ya habia ejecutado REGISTER
- 409 SENSOR_TYPE_MISMATCH: el sensor_id ya existe con un tipo de sensor diferente
- 422 INVALID_SENSOR_TYPE: tipo de sensor no permitido
- 500 INTERNAL_ERROR: error interno no recuperable para la solicitud

## 11. Logging minimo obligatorio del servidor
Para cada solicitud procesada registrar:
- timestamp
- ip y puerto de origen
- mensaje recibido
- respuesta enviada
- errores de red o protocolo

## 12. Ejemplo de sesion valida

```text
C: REGISTER SENSOR temp_01 temperatura
S: OK REGISTERED SENSOR temp_01
C: DATA temp_01 25.3
S: OK DATA_RECEIVED temp_01
C: GET_LAST temp_01
S: LAST temp_01 25.3 2026-04-14T10:30:00
```

## 13. Compatibilidad de implementacion actual
La implementacion actual del servidor ya soporta:
- PING
- REGISTER SENSOR
- REGISTER OPERATOR
- DATA
- GET_SENSORS
- GET_LAST
- ALERT para temperatura, humedad, vibracion, presion y consumo
- estados de conexion de sesion (unregistered, sensor, operator, disconnected)
- timeout real de inactividad en socket (30 segundos)
- concurrencia reforzada en broadcast de alertas (snapshot + limpieza de sockets fallidos)
- cierre controlado por senial (SIGINT/SIGTERM), cierre de socket de escucha y finalizacion ordenada

Como siguiente fase, se recomienda agregar pruebas de carga y evidencia de cierre bajo multiples clientes para fortalecer la sustentacion.
