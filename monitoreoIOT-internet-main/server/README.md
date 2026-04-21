# Servidor de Monitoreo IoT (C++)

## Compilar

```bash
cd server
./scripts/compilar.sh
```

## Ejecutar

```bash
./build/server 9000 logs/server.log
```

Tambien puede ejecutar con el formato solicitado por el curso:

```bash
./server 9000 logs/server.log
```

(En este repositorio el binario queda en `build/server` por CMake.)

## Protocolo implementado (fase 1)

- `PING`
- `REGISTER SENSOR <sensor_id> <tipo>`
- `REGISTER OPERATOR <operator_id>`
- `DATA <sensor_id> <valor>`
- `GET_SENSORS`
- `GET_LAST <sensor_id>`

Respuestas:

- `OK ...`
- `ERROR <codigo> <detalle>`
- `SENSORS ...`
- `LAST ...`
- `ALERT ...` (push a operadores)
