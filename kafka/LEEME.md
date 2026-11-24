# Kafka para GameShakers Chat (opcional)

Kafka es **opcional**. Sin Kafka, el servidor reparte los mensajes en memoria (así funciona en Render).
Con Kafka, cada mensaje se publica en Kafka y se reparte al consumirlo, y el historial de las salas sobrevive a un reinicio del servidor.

Kafka solo se instala en el **equipo del servidor**. Los clientes (`chat.jar`) no lo necesitan: hablan con el servidor por WebSocket.

## 1. Requisitos
- **Java 17 o superior** (el mismo que usan los `.jar`).
- **Kafka 4.4.0**: descargar `kafka_2.13-4.4.0.tgz` de https://kafka.apache.org/downloads (binario, no el `-src`).

## 2. Instalar en Windows (sin Docker)
1. Descomprime el `.tgz` (con 7-Zip o con `tar -xzf kafka_2.13-4.4.0.tgz` en PowerShell) y **renombra la carpeta a `C:\kafka`**.
   Usa una ruta corta y sin espacios: con rutas largas los scripts `.bat` de Kafka pueden fallar con *"La línea de entrada es demasiado larga"*.
2. Doble clic en **`kafka\iniciar-kafka.bat`** de este proyecto.
   - La primera vez formatea la carpeta `kafka\datos` (modo KRaft, sin ZooKeeper).
   - Después arranca Kafka. **Deja esa ventana abierta** mientras uses el chat.
   - Si Kafka está en otra carpeta: `set KAFKA_HOME=D:\ruta\kafka` antes de ejecutarlo.

Comandos equivalentes, por si prefieres escribirlos (en `cmd`, dentro de la carpeta `kafka` del proyecto):
```bat
C:\kafka\bin\windows\kafka-storage.bat random-uuid
C:\kafka\bin\windows\kafka-storage.bat format --standalone -t <ID-que-salió-arriba> -c chat-kafka.properties
C:\kafka\bin\windows\kafka-server-start.bat chat-kafka.properties
```
El formateo se hace **una sola vez**; las siguientes veces solo el último comando.

## 3. Linux / macOS
```bash
KAFKA_HOME=/ruta/a/kafka_2.13-4.4.0 ./iniciar-kafka.sh
```

## 4. Qué configura `chat-kafka.properties`
- Un solo nodo que hace de *broker* y *controller* (KRaft).
- **Escucha solo en `localhost:9092`**: el servidor de chat corre en el mismo equipo, así que Kafka no queda expuesto a la red.
- Datos en `kafka/datos` (se puede borrar con Kafka apagado para empezar de cero; habrá que formatear otra vez).

Luego arranca el servidor indicándole Kafka:
```bat
java -jar servidor.jar --chat.kafka.servidores=localhost:9092
```
(en IntelliJ: configuración **Servidor con Kafka**). Al encender crea, si no existe, el tema:

| Tema | Contenido | Retención |
|---|---|---|
| `gameshakers.chat` | mensajes de las 3 salas (clave = `xbox`, `playstation` o `steam`; valor = `hora\|autor\|texto`) | 7 días |

Para ver los temas o seguir el chat desde Kafka (útil para la exposición):
```bat
C:\kafka\bin\windows\kafka-topics.bat --bootstrap-server localhost:9092 --list
C:\kafka\bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092 --topic gameshakers.chat --from-beginning --formatter-property print.key=true
```

## Problemas conocidos
- **Kafka en Windows**: Apache no da soporte oficial a Kafka en Windows para producción. Funciona para desarrollo, pero se han reportado fallos al borrar segmentos viejos de los registros (errores de acceso a archivos). Si Kafka se cierra con un error de ese tipo, apágalo, borra `kafka\datos`, vuelve a ejecutar `iniciar-kafka.bat` (formatea de nuevo) y reinicia el servidor de chat. Alternativa más estable: ejecutar Kafka dentro de WSL2 con `iniciar-kafka.sh`.
- **"No se pudo conectar a Kafka en localhost:9092"** al encender el servidor con Kafka: Kafka no está encendido o todavía está arrancando; espera a ver `Kafka Server started` en su ventana.
