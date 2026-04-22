# Documentación Técnica — Persona 3: Operador, Web y Autenticación

## 1. Visión general

Esta documentación cubre los tres componentes desarrollados por la Persona 3 del proyecto de monitoreo IoT distribuido:

| Componente | Archivo | Lenguaje | Puerto |
|------------|---------|----------|--------|
| Cliente Operador | `operador/OperadorClient.java` | Java | — (cliente TCP) |
| Servicio de Autenticación | `web/auth_service.py` | Python | 8081 |
| Servidor Web | `web/web_server.py` | Python | 8080 |

Los tres componentes se integran con el servidor C++ (`server/`) y los sensores Python (`sensor/`) desarrollados por las otras personas del equipo, usando el protocolo **IOT-MONITOR-TEXT v1.0** definido en `docs/02-protocolo/especificacion-protocolo.md`.

---

## 2. Cliente Operador Java (`OperadorClient.java`)

### 2.1 Propósito

Permite a los ingenieros supervisar el sistema IoT desde una interfaz gráfica de escritorio.

### 2.2 Decisiones de diseño

**¿Por qué Java?**  
El enunciado requiere al menos dos lenguajes de programación. Java cumple con el requisito del cliente operador y su biblioteca Swing permite construir GUIs sin dependencias adicionales.

**¿Por qué Swing y no consola?**  
El enunciado especifica "interfaz gráfica sencilla". Swing es parte del JDK estándar (sin instalaciones extra) y permite mostrar sensores, mediciones y alertas en paneles separados.

### 2.3 Manejo de concurrencia

El mayor reto del cliente operador es que el servidor puede enviar mensajes `ALERT` en cualquier momento, mientras el operador también envía comandos y espera respuestas. Esto crea el problema de **mensajes entrelazados** en el mismo socket.

**Solución: dos colas de bloqueo**

```
Socket → HiloLector → ¿empieza con "ALERT "? ─── sí ──> alertQueue → HiloAlertConsumer → GUI
                                               └── no ──> responseQueue ← sendCommand() ← GUI
```

El `sendCommand()` escribe en el socket y luego hace `responseQueue.poll(8000ms)` — si el servidor no responde en 8 segundos, se registra el error sin colgar la GUI.

### 2.4 Resolución DNS

```java
// El hostname viene del campo de texto de la GUI — nunca una IP fija
InetAddress address = InetAddress.getByName(host);
Socket sock = new Socket();
sock.connect(new InetSocketAddress(address, port), 5_000);
```

Si `getByName()` lanza `UnknownHostException`, el error se muestra en la GUI y los campos de conexión vuelven a ser editables.

### 2.5 Comandos del protocolo usados

| Comando | Momento de uso |
|---------|---------------|
| `REGISTER OPERATOR <id>` | Al conectar (antes de exponer el socket al hilo lector) |
| `GET_SENSORS` | Al conectar (automático) + botón "Actualizar sensores" |
| `GET_LAST <sensor_id>` | Botón "Última medición" con sensor seleccionado |
| `PING` | Cada 15 s via hilo heartbeat (evita timeout de 30 s del servidor) |

### 2.6 Compilación y ejecución

```bash
cd operador
javac OperadorClient.java
java OperadorClient --host iot-monitoring.example.com --port 9000
```

---

## 3. Servicio de Autenticación (`auth_service.py`)

### 3.1 Propósito

El enunciado prohíbe almacenar usuarios en el servidor IoT principal. El servicio de autenticación es un proceso separado que actúa como **proveedor de identidad**.

Cuando alguien intenta iniciar sesión en la web, el servidor web consulta este servicio HTTP para validar credenciales.

### 3.2 Protocolo HTTP expuesto

```
POST /auth
Content-Type: application/json

{"username": "operador1", "password": "oper123"}
```

**Respuesta 200 OK (éxito):**
```json
{"status": "ok", "username": "operador1", "role": "operador", "nombre": "Operador Principal"}
```

**Respuesta 401 Unauthorized (error):**
```json
{"status": "error", "message": "Credenciales invalidas"}
```

### 3.3 Usuarios disponibles

| Usuario | Contraseña | Rol |
|---------|------------|-----|
| admin | admin123 | admin |
| operador1 | oper123 | operador |
| operador2 | oper456 | operador |
| invitado | guest | invitado |

### 3.4 Decisiones de implementación

- Se implementa con `http.server.BaseHTTPRequestHandler` de la biblioteca estándar de Python (sin Flask, Django, etc.).
- El handler procesa correctamente `Content-Length` para leer el cuerpo JSON sin bloqueos.
- Si falta el body o el JSON es inválido, responde `400 Bad Request` con mensaje descriptivo.
- Logs a stdout con timestamp para integración con el sistema de logging del equipo.

### 3.5 Ejecución

```bash
python3 auth_service.py --host 0.0.0.0 --port 8081
```

---

## 4. Servidor Web (`web_server.py`)

### 4.1 Propósito

Permite acceder al sistema de monitoreo desde cualquier navegador web, cumpliendo con el requisito de "interfaz web sencilla" del enunciado.

### 4.2 Rutas implementadas

| Método | Ruta | Auth requerida | Descripción |
|--------|------|---------------|-------------|
| GET | `/` | Sí | Dashboard: estado del sistema + sensores activos |
| GET | `/login` | No | Formulario de inicio de sesión |
| POST | `/login` | No | Procesa login; consulta al servicio de auth |
| GET | `/logout` | Sí | Cierra sesión |
| GET | `/api/sensors` | Sí | JSON: lista de sensores activos |
| GET | `/api/status` | No | JSON: estado general del sistema |

### 4.3 Implementación HTTP desde cero

Se cumple el requerimiento de "interpretar correctamente las cabeceras HTTP":

```python
# Leer Content-Length para saber cuántos bytes tiene el body del POST
content_length = int(self.headers.get("Content-Length", 0))
raw_body = self.rfile.read(content_length)

# Enviar respuesta con cabeceras correctas
self.send_response(200)
self.send_header("Content-Type", "text/html; charset=utf-8")
self.send_header("Content-Length", str(len(body)))
self.end_headers()
self.wfile.write(body)
```

### 4.4 Gestión de sesiones

Las sesiones se gestionan con una cookie `session_id` (UUID):

1. Al hacer login exitoso: se crea una sesión en el diccionario `_sessions` y se envía `Set-Cookie: session_id=<uuid>; HttpOnly`.
2. En cada request: se lee la cookie `Cookie: session_id=<uuid>` y se valida contra `_sessions`.
3. Al hacer logout: se elimina la sesión y se envía `Set-Cookie: session_id=; Max-Age=0` para limpiar la cookie del navegador.

### 4.5 Conexión al servidor IoT

El servidor web actúa como cliente del servidor IoT para obtener datos de sensores:

```python
# Clase IotClient — conexión bajo demanda, sin IPs hardcodeadas
addr_info = socket.getaddrinfo(self._host, self._port, ...)
sock.connect(sockaddr)
# REGISTER OPERATOR web_<uuid>
# GET_SENSORS
# → lista de sensores
# Cerrar conexión
```

Se usa un `operator_id` con UUID corto (ej. `web_3f7a9c12`) para evitar colisiones si hay múltiples peticiones concurrentes.

Si el servidor IoT no está disponible, `get_sensors()` captura la excepción, loguea el warning y retorna lista vacía — el dashboard se muestra igual, mostrando "Sin sensores activos".

### 4.6 Resolución de nombres

```python
#  DNS — no hay IPs en el código fuente
socket.getaddrinfo(hostname, port, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM)
```

El hostname llega por argumento de línea de comandos o variable de entorno. En producción en AWS, este hostname apunta a un registro Route 53.

### 4.7 Ejecución

```bash
python3 web_server.py \
  --iot-host iot-monitoring.example.com \
  --iot-port 9000 \
  --auth-host localhost \
  --auth-port 8081 \
  --web-port 8080
```

---

## 5. Integración con los demás componentes

### 5.1 Con el servidor C++ (Persona 1)

El cliente operador Java y el servidor web Python se conectan al servidor C++ usando exactamente el protocolo IOT-MONITOR-TEXT:

```
OperadorClient.java:
  "REGISTER OPERATOR operador_01\n"   →   "OK REGISTERED OPERATOR operador_01\n"
  "GET_SENSORS\n"                     →   "SENSORS 5 temp_01:temperatura ...\n"
  "GET_LAST temp_01\n"                →   "LAST temp_01 25.3 2026-04-19T10:30:00\n"

web_server.py (IotClient):
  "REGISTER OPERATOR web_3f7a9c12\n"  →   "OK REGISTERED OPERATOR web_3f7a9c12\n"
  "GET_SENSORS\n"                     →   "SENSORS 5 temp_01:temperatura ...\n"
```

### 5.2 Con los sensores Python (Persona 2)

No hay conexión directa entre los componentes de Persona 3 y los sensores. La comunicación es siempre a través del servidor central, lo que permite desacoplamiento total.

### 5.3 Flujo de alertas end-to-end

```
Sensor Python    →   Servidor C++   →   Cliente Java / Web
(DATA temp_01 55.0)   (detecta alerta)  (recibe ALERT temp_01 temperatura_alta 55.0 ...)
```

El cliente Java tiene un hilo lector que detecta líneas `ALERT ...` y las muestra en tiempo real en el área de alertas.

---

## 6. Puertos y configuración de red en AWS

Para el despliegue en la instancia EC2, los siguientes puertos deben estar abiertos en el Security Group:

| Puerto | Protocolo | Servicio | Acceso |
|--------|-----------|---------|--------|
| 9000 | TCP | Servidor IoT (C++) | Todos (0.0.0.0/0) |
| 8080 | TCP | Servidor Web | Todos (0.0.0.0/0) |
| 8081 | TCP | Servicio de Auth | Solo localhost (127.0.0.1) |

>  El servicio de autenticación (8081) no debe ser accesible desde Internet. Solo el servidor web lo consulta internamente.

---

## 7. Diagrama de arquitectura completa

```
Internet
    │
    ├──── HTTP :8080 ──────┐
    │                      ▼
    │              ┌──────────────────┐
    │              │  web_server.py   │──── localhost:8081 ──> auth_service.py
    │              │  (Python)        │
    │              └───────┬──────────┘
    │                      │ TCP :9000
    │                      │
    ├──── TCP :9000 ────────┤
    │                      ▼
    │              ┌──────────────────┐
    │              │  server (C++)    │◄─── TCP :9000 ──── Sensores Python
    │              │  Puerto 9000     │
    │              └──────────────────┘
    │                      ▲
    └──── TCP :9000 ────────┘
           Cliente Java
           OperadorClient.java
```

---

## 8. Resumen de cumplimiento de requisitos del enunciado

| Requisito | Componente | Cumplido |
|-----------|------------|---------|
| Cliente operador con GUI | `OperadorClient.java` (Swing) | check |
| Al menos 2 lenguajes (Java + otro) | Java + Python (ya cuentan C++ y Python de sensores) | check |
| Ver sensores activos | GET_SENSORS en Java y web | check |
| Recibir alertas en tiempo real | Hilo lector Java detecta ALERT push | check |
| Consultar mediciones | GET_LAST en Java y web | check |
| Servidor HTTP básico | `web_server.py` desde cero (stdlib) | check |
| Interpretar cabeceras HTTP | Content-Length, Content-Type, Cookie | check |
| Manejar GET | GET /, /login, /api/sensors, /api/status | check |
| Códigos de estado HTTP | 200, 302, 400, 401, 404 | check |
| Servicio externo de autenticación | `auth_service.py` separado | ✅ |
| Usuarios NO en el servidor IoT | auth_service.py es proceso independiente | ✅ |
| Sin IPs hardcodeadas | DNS via `InetAddress` (Java) y `getaddrinfo` (Python) | ✅ |
| Manejo de errores de red | Try/catch en todos los paths de red | ✅ |
