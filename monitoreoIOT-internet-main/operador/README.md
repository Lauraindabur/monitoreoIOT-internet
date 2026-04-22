# Operador - Cliente Java y relacion con la capa web

## Archivos revisados

- `operador/OperadorClient.java`: cliente de escritorio Swing para el operador.
- `web/web_server.py`: servidor HTTP del dashboard web.
- `web/auth_service.py`: servicio externo de autenticacion consumido por la web.

## Que hace el modulo operador

El cliente Java del operador se conecta por TCP al servidor IoT, se registra con `REGISTER OPERATOR <id>` y trabaja sobre el protocolo `IOT-MONITOR-TEXT v1.0`.

Desde la interfaz se puede:

- visualizar sensores activos
- consultar la ultima medicion de un sensor
- mantener supervision automatica por sensor o global
- recibir alertas `ALERT ...` en tiempo real
- ejecutar comandos permitidos sin bloquear la GUI

## Flujo real implementado

1. El operador ingresa `host`, `port` e `ID operador`.
2. El cliente resuelve DNS con `InetAddress.getByName(...)`.
3. Se abre la conexion TCP y se envia `REGISTER OPERATOR <id>`.
4. Si el servidor responde `OK REGISTERED OPERATOR <id>`, se activan tres hilos: lector de socket, heartbeat con `PING` y supervision periodica.
5. El panel inicial hace un refresco completo con `GET_SENSORS` y luego `GET_LAST <sensor_id>` para cada sensor activo.
6. Las lineas que empiezan por `ALERT ` se separan de las respuestas normales y se procesan en segundo plano.

## Interfaz del operador

La ventana queda dividida en estas zonas:

- `Conexion al servidor`: host, puerto, ID del operador y boton conectar/desconectar.
- `Resumen superior`: sensores activos, mediciones recientes, alertas y modo de supervision.
- `Sensores activos y supervision`: tabla principal, detalle del sensor seleccionado y botones de supervision.
- `Mediciones / Acciones / Alertas / Log`: historial reciente, consola de comandos permitidos, alertas push y bitacora de sesion.

## Acciones reales del cliente Java

Las acciones visibles en `OperadorClient.java` no controlan sensores de forma remota; trabajan con los comandos de consulta ya soportados por el servidor:

- `Actualizar panel`: ejecuta `GET_SENSORS` y luego `GET_LAST` para los sensores activos.
- `Consultar medicion`: consulta `GET_LAST` del sensor seleccionado.
- `Supervisar sensor`: agrega el sensor seleccionado al monitoreo automatico local.
- `Quitar supervision`: elimina el sensor de la supervision local.
- `Supervisar todos`: activa o pausa la supervision periodica de todos los sensores activos.
- `GET_SENSORS`, `PING` y `GET_LAST`: tambien pueden lanzarse desde la pestaña de acciones.
- `Limpiar historial` y `Limpiar alertas`: limpian solo el estado local de la interfaz.

## Protocolo que maneja el operador

| Entrada o comando | Uso en el cliente |
|---|---|
| `REGISTER OPERATOR <id>` | Registro inicial del operador |
| `OK REGISTERED OPERATOR <id>` | Confirmacion esperada para abrir la sesion |
| `GET_SENSORS` | Solicita sensores activos |
| `SENSORS <n> sensor_id:tipo ...` | Respuesta usada para poblar la tabla |
| `GET_LAST <sensor_id>` | Solicita la ultima medicion |
| `LAST <sensor_id> <valor> <timestamp>` | Respuesta usada para detalle e historial |
| `PING` | Heartbeat de sesion |
| `OK PONG` | Respuesta esperada al heartbeat |
| `ALERT <sensor_id> <codigo> <valor> <timestamp>` | Evento push procesado en tiempo real |
| `ERROR 404 NO_DATA` | El sensor existe pero aun no tiene mediciones |
| `ERROR 404 SENSOR_NOT_FOUND` | El sensor ya no esta disponible |

## Relacion con `web/web_server.py`

El cliente Java y el dashboard web son dos interfaces distintas para el mismo backend IoT.

Lo importante que quedo implementado en `web/web_server.py` es:

- login web en `/login` con cookie `session_id`
- cierre de sesion en `/logout`
- consultas autenticadas a `/api/dashboard`, `/api/sensors`, `/api/alerts` y `/api/measurements`
- endpoint `/api/status` para exponer el estado actual del sistema
- acciones POST para refrescar el dashboard y marcar alertas como revisadas
- endpoint `/api/commands` que solo permite `PING`, `GET_SENSORS` y `GET_LAST`
- uso de `dashboard_service.py` para mantener snapshot, mediciones recientes y stream de alertas

En otras palabras:

- el cliente Java del operador no pide usuario ni contrasena
- la autenticacion del proyecto vive en la capa web
- el servidor IoT principal no almacena usuarios locales

## Servicio externo de autenticacion

`web/auth_service.py` expone:

- `POST /auth` para validar credenciales y devolver el rol
- `GET /health` para verificar disponibilidad

El servidor web consulta este servicio antes de crear la sesion del dashboard. El cliente Java del operador no consume este servicio directamente.

## Aspectos tecnicos importantes

- Resolucion de nombres: el operador usa DNS real; no hay IPs hardcodeadas.
- Tolerancia a fallos: si se cae la conexion, la GUI sigue viva y permite reconectar.
- Concurrencia: los comandos salientes se serializan para evitar cruces entre `PING`, `GET_SENSORS` y `GET_LAST`.
- Supervision local: el modo individual y el modo global se ejecutan del lado cliente.
- Alertas y mediciones: el historial se alimenta tanto por `GET_LAST` como por las alertas push.
- Umbrales visuales: el operador marca estados como `Normal`, `Atencion` o `Alerta` segun tipo y valor recibido.

## Requisitos

- Java 11 o superior
- Acceso al servidor IoT por nombre de dominio y puerto
- Si se usa la capa web, Python 3 para `web_server.py` y `auth_service.py`

## Compilar

Si `javac` ya esta en el `PATH`:

```bash
cd operador
javac OperadorClient.java
```

Si en Windows el JDK esta instalado pero `javac` no esta en el `PATH`, puedes usar la ruta completa:

```powershell
cd operador
& "C:\Program Files\Java\jdk-26.0.1\bin\javac.exe" OperadorClient.java
```

## Ejecutar

Con `java` en el `PATH`:

```bash
cd operador
java OperadorClient
java OperadorClient --host iot-monitoring.example.com --port 9000
```

Con variables de entorno:

```bash
IOT_SERVER_HOST=iot-monitoring.example.com IOT_SERVER_PORT=9000 java OperadorClient
```

En Windows, si necesitas ruta completa:

```powershell
cd operador
& "C:\Program Files\Java\jdk-26.0.1\bin\java.exe" OperadorClient --host localhost --port 9000
```
