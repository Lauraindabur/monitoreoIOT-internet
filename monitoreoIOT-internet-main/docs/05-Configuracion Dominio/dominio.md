# Configuración de DNS con DuckDNS

## Descripción

Para cumplir con el requisito de resolución de nombres, el sistema fue configurado para evitar el uso de direcciones IP codificadas. En su lugar, todos los servicios se acceden mediante un nombre de dominio, el cual es resuelto dinámicamente a la dirección IP de la instancia en la nube.

---

## Dominio utilizado

Se utilizó un dominio gratuito proporcionado por DuckDNS:

mymonitoriot.duckdns.org

Este dominio actúa como un alias que apunta a la dirección IP pública de la instancia EC2.

---

## Infraestructura en AWS

El sistema fue desplegado en una instancia de Amazon EC2 con las siguientes características:

- Proveedor: AWS
- Tipo: EC2 (Elastic Compute Cloud)
- IP pública: dinámica (asignada por AWS)
- Servicios desplegados:
  - Servidor IoT (puerto 9000)
  - Servicio de autenticación (puerto 8081)
  - Servidor web (puerto 8080)

---

## Asociación dominio a IP

El dominio de DuckDNS se configuró para apuntar a la IP pública de la instancia EC2.

Ejemplo:

mymonitoriot.duckdns.org → 54.173.238.12

Cuando un cliente utiliza el dominio, ocurre el siguiente proceso:

1. El sistema realiza una consulta DNS
2. El servicio DuckDNS responde con la IP asociada
3. El cliente establece la conexión TCP con dicha IP

---

## Actualización de la IP

Dado que la IP pública de la instancia EC2 puede cambiar cuando se detiene y se inicia nuevamente, es necesario actualizar el dominio en DuckDNS.

Pasos:

1. Obtener la nueva IP pública desde la consola de AWS EC2
2. Acceder a DuckDNS
3. Actualizar el dominio con la nueva IP
4. Esperar unos segundos para la propagación

---

## Uso del dominio en el sistema

Todos los componentes del sistema utilizan el dominio en lugar de direcciones IP.

### Servidor web

python3 web_server.py \
  --web-host 0.0.0.0 \
  --web-port 8080 \
  --iot-host mymonitoriot.duckdns.org \
  --iot-port 9000 \
  --auth-host mymonitoriot.duckdns.org \
  --auth-port 8081

---

### Sensores

python -m sensor.run_many \
  --host mymonitoriot.duckdns.org \
  --port 9000

---

### Operador (Java)

java OperadorClient --host mymonitoriot.duckdns.org --port 9000

---

## Manejo de errores de resolución

El sistema implementa manejo de errores en caso de fallos en la resolución DNS:

- Se captura la excepción de resolución (socket.gaierror)
- Se evita la terminación abrupta del programa
- Se permite reintentar la conexión

Esto permite que el sistema continúe funcionando ante fallos temporales de red.

---

## Cumplimiento del requisito

- No se utilizan direcciones IP codificadas en el código fuente
- Todos los servicios se localizan mediante nombres de dominio
- Se utiliza resolución DNS antes de establecer conexiones
- Se implementa manejo de errores ante fallos de resolución

---

## Acceso al sistema

El sistema puede ser accedido desde un navegador mediante:

http://mymonitoriot.duckdns.org:8080

---

## Conclusión

El uso de DuckDNS permitió implementar una solución de resolución de nombres sin costo, desacoplando el sistema de direcciones IP específicas y cumpliendo con los requerimientos del proyecto.