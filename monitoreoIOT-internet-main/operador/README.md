# Operador - Cliente Java con interfaz grafica

## Que hace

`OperadorClient.java` es el cliente de escritorio para supervision del sistema IoT.
Se conecta por TCP al servidor central y usa el protocolo `IOT-MONITOR-TEXT v1.0`.

Desde la interfaz el operador puede:

- visualizar sensores activos
- recibir alertas generadas por el sistema en tiempo real
- consultar mediciones recientes
- ejecutar acciones de supervision sin bloquear la GUI

## Requisitos

- Java 11 o superior
- Acceso al servidor IoT por nombre de dominio y puerto

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

## Interfaz

La ventana esta organizada en cuatro zonas:

- `Conexion al servidor`: host, puerto, ID del operador y boton conectar/desconectar.
- `Resumen superior`: tarjetas con sensores activos, mediciones recientes, alertas y modo de supervision.
- `Sensores activos y supervision`: tabla principal, detalle del sensor seleccionado y botones de accion.
- `Mediciones / Acciones / Alertas / Log`: historial reciente, consola de comandos al servidor, eventos push del servidor y bitacora de sesion.

## Acciones de supervision incluidas

El protocolo actual del servidor no define un comando remoto de control para operadores, asi que las acciones de supervision se implementan del lado cliente sobre las operaciones existentes:

- `Actualizar panel`: ejecuta `GET_SENSORS` y consulta `GET_LAST` para los sensores activos.
- `Consultar medicion`: ejecuta `GET_LAST` del sensor seleccionado.
- `Supervisar sensor`: agrega un sensor al monitoreo automatico local.
- `Supervisar todos`: activa supervision periodica para todos los sensores activos.
- `Limpiar historial` y `Limpiar alertas`: limpian las vistas locales sin cerrar la sesion.

## Protocolo usado

| Comando | Uso |
|---|---|
| `REGISTER OPERATOR <id>` | Registro inicial del operador |
| `GET_SENSORS` | Lista de sensores activos |
| `GET_LAST <sensor_id>` | Ultima medicion disponible |
| `PING` | Heartbeat para mantener la sesion |
| `ALERT ...` | Notificacion push enviada por el servidor |

## Aspectos tecnicos importantes

- Resolucion de nombres: usa `InetAddress.getByName(host)`; no hay IPs hardcodeadas.
- Alertas en tiempo real: un hilo lector separa `ALERT` de las respuestas normales.
- Errores de red: la aplicacion mantiene la GUI viva y permite reconectar sin cerrarse.
- Concurrencia interna: los comandos salientes se serializan para evitar cruces entre `PING`, `GET_SENSORS` y `GET_LAST`.
- Mediciones recientes: se construyen localmente a partir de consultas `GET_LAST` y de alertas recibidas.

## Nota sobre usuarios

El cliente operador no almacena usuarios en el servidor IoT principal. Ese requisito se cumple en el proyecto completo con el servicio externo de autenticacion del modulo web.
