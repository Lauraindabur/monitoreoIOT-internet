# Web — Interfaz web y servicio de autenticación

Este directorio contiene **dos servicios Python** que forman la capa web del sistema IoT:

| Archivo | Puerto | Descripción |
|---------|--------|-------------|
| `auth_service.py` | `8081` | Servicio externo de autenticación (validación de usuarios y roles) |
| `web_server.py` | `8080` | Servidor HTTP con dashboard web del sistema IoT |

---

## Requisitos

- **Python 3.8** o superior  
- Sin dependencias externas (solo biblioteca estándar)

---

## Cómo ejecutar

### 1. Levantar el servicio de autenticación

```bash
cd web
python3 auth_service.py

# Con parámetros explícitos
python3 auth_service.py --host 0.0.0.0 --port 8081
```

### 2. Levantar el servidor web

```bash
# En otra terminal
python3 web_server.py

# Con parámetros (importante en producción)
python3 web_server.py \
  --iot-host iot-monitoring.example.com \
  --iot-port 9000 \
  --auth-host localhost \
  --auth-port 8081 \
  --web-port 8080
```

### 3. Abrir el navegador

```
http://localhost:8080
```

---

## Variables de entorno

Ambos servicios leen variables de entorno como alternativa a los argumentos:

| Variable | Servicio | Descripción |
|----------|----------|-------------|
| `IOT_SERVER_HOST` | web_server | Host DNS del servidor IoT |
| `IOT_SERVER_PORT` | web_server | Puerto del servidor IoT |
| `AUTH_HOST` | web_server | Host del servicio de auth |
| `AUTH_PORT` | web_server / auth_service | Puerto del servicio de auth |
| `WEB_HOST` | web_server | Interfaz de red del servidor web |
| `WEB_PORT` | web_server | Puerto del servidor web |

Ejemplo con variables de entorno:

```bash
export IOT_SERVER_HOST=iot-monitoring.example.com
export IOT_SERVER_PORT=9000
python3 web_server.py
```

---

## `auth_service.py` — Servicio de autenticación

### ¿Por qué existe?

El servidor IoT **no almacena usuarios localmente**. Cuando alguien inicia sesión en la web, el `web_server.py` consulta este servicio para validar las credenciales y obtener el rol del usuario.

Esto simula una arquitectura real con separación de responsabilidades (Identity Provider vs. servidor de negocio).

### Rutas HTTP

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/auth` | Valida credenciales. Body: `{ "username": "...", "password": "..." }` |
| `GET`  | `/health` | Healthcheck: `{ "status": "ok" }` |

### Respuestas de `/auth`

**Éxito (200 OK):**
```json
{
  "status": "ok",
  "username": "operador1",
  "role": "operador",
  "nombre": "Operador Principal"
}
```

**Error (401 Unauthorized):**
```json
{
  "status": "error",
  "message": "Credenciales invalidas"
}
```

### Usuarios de prueba

| Usuario | Contraseña | Rol |
|---------|------------|-----|
| `admin` | `admin123` | admin |
| `operador1` | `oper123` | operador |
| `operador2` | `oper456` | operador |
| `invitado` | `guest` | invitado |

### Ejemplo con `curl`

```bash
curl -X POST http://localhost:8081/auth \
  -H "Content-Type: application/json" \
  -d '{"username": "operador1", "password": "oper123"}'
```

---

## `web_server.py` — Servidor HTTP

### Rutas HTTP

| Método | Ruta | Descripción |
|--------|------|-------------|
| `GET`  | `/` | Dashboard (requiere login). Muestra sensores activos. |
| `GET`  | `/login` | Formulario de inicio de sesión. |
| `POST` | `/login` | Procesa el login. Consulta `/auth` del servicio externo. |
| `GET`  | `/logout` | Cierra la sesión. |
| `GET`  | `/api/sensors` | JSON con sensores activos (requiere login). |
| `GET`  | `/api/status` | JSON con estado del sistema. |

### Flujo completo de autenticación

```
Navegador          web_server.py        auth_service.py      Servidor IoT
    │                    │                    │                    │
    │── GET /login ─────>│                    │                    │
    │<── formulario ─────│                    │                    │
    │                    │                    │                    │
    │── POST /login ────>│                    │                    │
    │  user+password     │── POST /auth ─────>│                    │
    │                    │<── { role:op } ────│                    │
    │                    │── crear sesión     │                    │
    │<── 302 /  (cookie)─│                    │                    │
    │                    │                    │                    │
    │── GET / ──────────>│                    │                    │
    │   (con cookie)     │── TCP connect ────────────────────────>│
    │                    │── REGISTER OPERATOR web_xxx ──────────>│
    │                    │── GET_SENSORS ─────────────────────────>│
    │                    │<── SENSORS 5 ... ──────────────────────│
    │<── HTML dashboard ─│                    │                    │
```

### Implementación HTTP desde cero

El servidor implementa HTTP/1.1 usando `http.server.BaseHTTPRequestHandler` de la biblioteca estándar:

- ✅ Interpreta correctamente las cabeceras HTTP (`Content-Type`, `Content-Length`, `Cookie`)
- ✅ Maneja peticiones `GET` y `POST`
- ✅ Devuelve los códigos de estado correctos (`200`, `302`, `400`, `401`, `404`)
- ✅ Gestiona cookies de sesión (`Set-Cookie` / lectura de `Cookie`)

### Resolución de nombres (sin IPs hardcodeadas)

El servidor web nunca tiene IPs fijas. Para conectarse al servidor IoT usa:

```python
# ✅ Resolución DNS — no hay ninguna IP en el código
addr_info = socket.getaddrinfo(
    host, port,
    family=socket.AF_UNSPEC,
    type=socket.SOCK_STREAM
)
```

Si el DNS falla, `get_sensors()` captura la excepción, loguea el error y retorna lista vacía (el sistema no se cae).

---

## Diagrama de arquitectura de la capa web

```
┌──────────────────────────────────────────────────────┐
│                    EC2 (AWS)                         │
│                                                      │
│  ┌──────────────┐    ┌──────────────────────────┐   │
│  │ auth_service │    │      web_server.py        │   │
│  │   :8081      │<───│          :8080            │   │
│  │              │    │                          │   │
│  │ - Valida     │    │ - Dashboard HTML          │   │
│  │   usuarios   │    │ - Sesiones con cookies    │   │
│  │ - Retorna    │    │ - Consulta IoT via TCP    │   │
│  │   roles      │    │                          │   │
│  └──────────────┘    └────────────┬─────────────┘   │
│                                   │ TCP :9000        │
│                      ┌────────────▼─────────────┐   │
│                      │     server (C++)          │   │
│                      │         :9000             │   │
│                      └──────────────────────────┘   │
└──────────────────────────────────────────────────────┘
          ▲
          │ HTTP :8080
     Navegador web del usuario
```
