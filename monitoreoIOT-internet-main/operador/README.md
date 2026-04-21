# Operador — Cliente Java con interfaz gráfica

## ¿Qué es esto?

El **cliente operador** es la aplicación con la que los ingenieros supervisan el sistema IoT en tiempo real.  
Está escrito en Java (sin dependencias externas) y se comunica con el servidor central usando el **Protocolo IOT-MONITOR-TEXT v1.0** sobre TCP.

---

## Requisitos

- **Java 11** o superior
- Acceso de red al servidor IoT (puerto configurado, por defecto `9000`)

---

## Compilar

```bash
cd operador
javac OperadorClient.java
```

---

## Ejecutar

```bash
# Con los valores por defecto (localhost:9000)
java OperadorClient

# Especificando host y puerto
java OperadorClient --host iot-monitoring.example.com --port 9000

# También se pueden usar variables de entorno
IOT_SERVER_HOST=iot-monitoring.example.com IOT_SERVER_PORT=9000 java OperadorClient
```

---

## Interfaz gráfica

La ventana tiene tres secciones:

| Sección | Descripción |
|---------|-------------|
| **Panel de conexión** (arriba) | Campos para host, puerto e ID de operador. Botón Conectar/Desconectar. |
| **Sensores activos** (izquierda) | Tabla con los sensores registrados. Botones para refrescar y consultar última medición. |
| **Alertas en tiempo real** (derecha arriba) | Mensajes `ALERT` que el servidor envía automáticamente. |
| **Log de sesión** (derecha abajo) | Registro de todos los comandos enviados y respuestas recibidas. |

---

## Flujo de conexión

```
GUI (EDT)                     Hilo de fondo             Servidor IoT
   │                               │                          │
   │── [Conectar] ────────────────>│                          │
   │                               │── DNS lookup ──────────>│
   │                               │<─ IP resuelta ──────────│
   │                               │── TCP connect ─────────>│
   │                               │── REGISTER OPERATOR ──>│
   │                               │<─ OK REGISTERED ────────│
   │<── setConnectedState(true) ───│                          │
   │                               │                          │
   │── [Actualizar sensores] ──────│── GET_SENSORS ─────────>│
   │                               │<─ SENSORS 5 ... ────────│
   │<── tabla actualizada ─────────│                          │
   │                               │                          │
   │                     [Lector en background]               │
   │                               │<─ ALERT temp_01 ... ────│  ← push asíncrono
   │<── área de alertas ───────────│                          │
```

---

## Arquitectura interna

El cliente usa **tres hilos de fondo** para no bloquear la GUI de Swing:

| Hilo | Nombre | Función |
|------|--------|---------|
| `socket-reader` | Hilo lector | Lee líneas del socket. Si empieza con `ALERT ` → cola de alertas; si no → cola de respuestas. |
| `alert-consumer` | Consumidor de alertas | Saca alertas de la cola y actualiza el área de alertas en la GUI. |
| `heartbeat` | Heartbeat | Envía `PING` cada 15 segundos para evitar el timeout del servidor (30s). |

Todas las actualizaciones de la GUI se hacen con `SwingUtilities.invokeLater()` para respetar el EDT.

---

## Comandos del protocolo usados

| Comando | Cuándo | Respuesta esperada |
|---------|--------|--------------------|
| `REGISTER OPERATOR <id>` | Al conectar | `OK REGISTERED OPERATOR <id>` |
| `GET_SENSORS` | Botón "Actualizar sensores" | `SENSORS <n> <id>:<tipo> ...` |
| `GET_LAST <sensor_id>` | Botón "Última medición" | `LAST <id> <valor> <timestamp>` |
| `PING` | Cada 15 segundos (heartbeat) | `OK PONG` |
| *(recibido)* `ALERT ...` | Automático desde el servidor | — |

---

## Resolución de nombres (sin IPs hardcodeadas)

El código usa `InetAddress.getByName(hostname)` para resolver el dominio antes de conectar.  
Si el DNS falla, se muestra un error claro en la GUI sin que la aplicación crashee.

```java
// ✅ Correcto — resolución DNS
InetAddress address = InetAddress.getByName(host);
new Socket().connect(new InetSocketAddress(address, port), 5_000);

// ❌ Prohibido — IP hardcodeada
new Socket("192.168.1.100", 9000);
```

---

## Manejo de errores

- **Error de DNS**: diálogo con mensaje claro + log de sesión.
- **Conexión rechazada**: diálogo + log. Los campos vuelven a ser editables.
- **Pérdida de conexión** (durante la sesión): el hilo lector detecta EOF y actualiza el estado automáticamente.
- **Timeout de respuesta**: si el servidor no responde en 8 segundos, se loguea el error sin colgar la GUI.
