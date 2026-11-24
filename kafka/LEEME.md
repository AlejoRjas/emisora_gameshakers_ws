# Kafka para GameShakers Radio

Kafka solo se instala en el **equipo del servidor**. Locutores y oyentes no lo necesitan: ellos hablan con el servidor por WebSocket.

## 1. Requisitos
- **Java 17 o superior** (el mismo que usan los `.jar`).
- **Kafka 4.4.0**: descargar `kafka_2.13-4.4.0.tgz` de https://kafka.apache.org/downloads (binario, no el `-src`).

## 2. Instalar en Windows (sin Docker)
1. Descomprime el `.tgz` (con 7-Zip o con `tar -xzf kafka_2.13-4.4.0.tgz` en PowerShell) y **renombra la carpeta a `C:\kafka`**.
   Usa una ruta corta y sin espacios: con rutas largas los scripts `.bat` de Kafka pueden fallar con *"La línea de entrada es demasiado larga"*.
2. Doble clic en **`kafka\iniciar-kafka.bat`** de este proyecto.
   - La primera vez formatea la carpeta `kafka\datos` (modo KRaft, sin ZooKeeper).
   - Después arranca Kafka. **Deja esa ventana abierta** mientras uses la radio.
   - Si Kafka está en otra carpeta: `set KAFKA_HOME=D:\ruta\kafka` antes de ejecutarlo.

Comandos equivalentes, por si prefieres escribirlos (en `cmd`, dentro de la carpeta `kafka` del proyecto):
```bat
C:\kafka\bin\windows\kafka-storage.bat random-uuid
C:\kafka\bin\windows\kafka-storage.bat format --standalone -t <ID-que-salió-arriba> -c radio-kafka.properties
C:\kafka\bin\windows\kafka-server-start.bat radio-kafka.properties
```
El formateo se hace **una sola vez**; las siguientes veces solo el último comando.

## 3. Linux / macOS
```bash
KAFKA_HOME=/ruta/a/kafka_2.13-4.4.0 ./iniciar-kafka.sh
```

## 4. Qué configura `radio-kafka.properties`
- Un solo nodo que hace de *broker* y *controller* (KRaft).
- **Escucha solo en `localhost:9092`**: el servidor de la radio corre en el mismo equipo, así que Kafka no queda expuesto a la red.
- Datos en `kafka/datos` (se puede borrar con Kafka apagado para empezar de cero; habrá que formatear otra vez).

El servidor de la radio crea solo los temas que necesita al encenderse:

| Tema | Contenido | Retención |
|---|---|---|
| `radio.xbox.audio` | audio MP3 de la sala Xbox | 30 min |
| `radio.playstation.audio` | audio MP3 de la sala PlayStation | 30 min |
| `radio.steam.audio` | audio MP3 de la sala PC · Steam | 30 min |
| `radio.chat` | chat de las 3 salas (clave = id de la sala) | 24 h |

Para ver los temas o espiar el chat (útil para la exposición):
```bat
C:\kafka\bin\windows\kafka-topics.bat --bootstrap-server localhost:9092 --list
C:\kafka\bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092 --topic radio.chat --from-beginning --property print.key=true
```

## Problemas conocidos
- **Kafka en Windows**: Apache no da soporte oficial a Kafka en Windows para producción. Funciona para desarrollo, pero se han reportado fallos al borrar segmentos viejos de los registros (errores de acceso a archivos). Si Kafka se cierra con un error de ese tipo, apágalo, borra `kafka\datos`, vuelve a ejecutar `iniciar-kafka.bat` (formatea de nuevo) y reinicia el servidor de la radio. Alternativa más estable: ejecutar Kafka dentro de WSL2 con `iniciar-kafka.sh`.
- **"No se pudo conectar a Kafka en localhost:9092"** al encender el servidor: Kafka no está encendido o todavía está arrancando; espera a ver `Kafka Server started` en su ventana.
