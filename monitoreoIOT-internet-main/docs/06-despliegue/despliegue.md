# Despliegue del sistema en AWS
 
## Descripción
 
El sistema de monitoreo IoT fue desplegado en una instancia de Amazon EC2, donde se ejecutan los servicios principales: servidor IoT, servicio de autenticación, servidor web y los sensores simulados.

El sistema cuenta con dos tipos de clientes operador:

- Cliente operador en Java (ejecutado localmente)
- Cliente operador en Python integrado en el servidor web (accesible desde navegador)

Ambos se conectan al servidor IoT utilizando el dominio configurado.

---
 
## Infraestructura utilizada
 
El despliegue se realizó sobre una instancia EC2 de AWS con una IP pública dinámica.  
Para permitir el acceso externo a los servicios, se configuraron reglas en el Security Group habilitando los siguientes puertos:
 
- 9000 (Servidor IoT)
- 8081 (Servicio de autenticación)
- 8080 (Servidor web)
Adicionalmente, se utilizó el dominio:
 
```
mymonitoriot.duckdns.org
```
 
el cual fue asociado a la IP pública de la instancia mediante DuckDNS.
 
---
 
## Ejecución de los servicios en la instancia EC2
 
Una vez conectado a la instancia mediante SSH, se iniciaron los distintos componentes del sistema en terminales separadas.
 
### Servidor IoT
 
El servidor principal se ejecutó desde el directorio correspondiente:
 
```bash
cd server/src
./server 9000 log2.log
```
 
Este proceso quedó escuchando conexiones TCP en el puerto 9000.
 
### Servicio de autenticación
 
El servicio de autenticación se ejecutó con el siguiente comando:
 
```bash
python3 auth_service.py --host 0.0.0.0 --port 8081
```
 
El uso de `0.0.0.0` permitió aceptar conexiones externas a través del dominio.
 
### Servidor web
 
El servidor web se inició configurando la comunicación con los demás servicios mediante el dominio:
 
```bash
python3 web_server.py \
  --web-host 0.0.0.0 \
  --web-port 8080 \
  --iot-host mymonitoriot.duckdns.org \
  --iot-port 9000 \
  --auth-host mymonitoriot.duckdns.org \
  --auth-port 8081
```

El servidor web cumple dos funciones:
 
1. Proporciona acceso al sistema desde un navegador
2. Actúa como cliente operador implementado en Python
Esto permite interactuar con el sistema sin necesidad de ejecutar el cliente Java.


Este servicio quedó disponible para acceso desde navegador en:
 
```
http://mymonitoriot.duckdns.org:8080
```
 
### Sensores simulados
 
Los sensores se ejecutaron en la misma instancia, enviando datos al servidor IoT mediante el dominio:
 
```bash
python3 -m sensor.run_many \
  --host mymonitoriot.duckdns.org \
  --port 9000
```
 
Esto permitió validar la comunicación distribuida utilizando resolución DNS en lugar de direcciones IP.
 
---
 
## Ejecución del cliente operador (Java)
 
El cliente operador no fue desplegado en AWS.  
El cleinte operador en java, a diferencia del hecho en python fue desarrollado para trabajar localmente desde el equipo del usuario, estableciendo conexión remota con el servidor IoT en la instancia EC2.
 
Primero se compiló:
 
```bash
javac OperadorClient.java
```
 
Luego se ejecutó:
 
```bash
java OperadorClient --host mymonitoriot.duckdns.org --port 9000
```
 
De esta forma, el cliente utilizó el dominio para conectarse al servidor en la nube, cumpliendo con el requisito de no utilizar direcciones IP codificadas.
 
---

## Cliente operador en Python (web)

El sistema incluye una segunda forma de operación: un cliente implementado en Python dentro del servidor web.

Este cliente:

1. Se comunica con el servidor IoT usando el dominio
2. Utiliza el servicio de autenticación
3. Es completamente accesible desde internet mediante navegador

Esto lo convierte en una alternativa al cliente Java, permitiendo operar el sistema sin necesidad de ejecutar código local.

---
 
## Integración mediante DNS
 
Todos los componentes del sistema (sensores, servidor web y cliente operador) utilizan el dominio:
 
```
mymonitoriot.duckdns.org
```
 
para localizar los servicios.  
Esto permite que la resolución de nombres convierta el dominio en la dirección IP correspondiente antes de establecer cada conexión.
 
---
 
## Resultado del despliegue
 
El sistema quedó completamente funcional en la nube, con comunicación entre todos sus componentes mediante DNS.  
El acceso al sistema se realiza a través del navegador y mediante clientes externos sin necesidad de conocer la dirección IP de la instancia.
 
---
 
## Consideración sobre la IP dinámica
 
Dado que la IP pública de la instancia EC2 es dinámica, en caso de reinicio de la instancia se actualizó manualmente el registro en DuckDNS para mantener la asociación entre el dominio y la nueva IP.
 