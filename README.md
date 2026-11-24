# GameShakers Chat · WebSocket (Spring Boot)

Chat con **tres salas**: **Xbox**, **PlayStation** y **PC · Steam**, sobre **WebSocket**.
Un servidor Spring Boot aloja las salas; cada persona usa el cliente de escritorio `chat.jar`, elige sala, chatea, ve quién está y puede cambiarse de sala.

| Carpeta | Programa | Genera |
|---|---|---|
| [`servidor/`](servidor) | Servidor Spring Boot + WebSocket (Kafka opcional) | `servidor.jar` |
| [`chat/`](chat) | Cliente de chat de escritorio (Java Swing) | `chat.jar` |
| [`kafka/`](kafka) | Configuración y scripts para usar Kafka (opcional) | — |
| `Dockerfile`, `render.yaml` | Para publicar el servidor en Render | — |

## Arquitectura

```
  chat.jar (Ana)  ─┐                         ┌──────────── SERVIDOR (Spring Boot) ─────────────┐
  chat.jar (Luis) ─┼── WebSocket /chat ────► │  ChatHandler → SalasChat                        │
  chat.jar (...)  ─┘   ws:// o wss://        │   Xbox │ PlayStation │ PC·Steam                  │
        ▲                                    │   (usuarios + historial de cada sala)          │
        └──── mensajes de SU sala ◄──────────│   Difusor: memoria  ─o─  Kafka (opcional)      │
                                             │   GET /  y  /api/salas: estado en el navegador  │
                                             └─────────────────────────────────────────────────┘
```

- Cada mensaje pasa por el **difusor** antes de repartirse solo a la sala correspondiente:
  - **En memoria** (por defecto): no hay que instalar nada; es lo que se usa en Render.
  - **Kafka** (opcional): se publica en el tema `gameshakers.chat` (clave = sala) y se reparte al consumirlo; el historial queda guardado y se recupera si el servidor se reinicia.
- Al entrar o cambiar de sala se reciben los **últimos 50 mensajes** de esa sala.
- La página **`/`** del servidor muestra en el navegador las 3 salas, quién está y cuántos mensajes hay (se actualiza cada 5 s); **`/api/salas`** da lo mismo en JSON.

## Probar en tu computador

Requisito: **Java 17 o superior** ([Eclipse Temurin](https://adoptium.net/)).

```bash
java -jar servidor.jar                 # servidor en http://localhost:8080  (WebSocket: ws://localhost:8080/chat)
java -jar chat.jar                     # abre el cliente; abre varios para chatear entre ellos
java -jar chat.jar 192.168.1.20:8080   # cliente apuntando a otro equipo de tu red
```

En `chat.jar`: escribe la dirección del servidor y tu nombre, haz clic en una sala y pulsa **Conectar**. Para cambiarte, clic en otra sala.

Con Kafka (opcional, ver [`kafka/LEEME.md`](kafka/LEEME.md)):
```bash
java -jar servidor.jar --chat.kafka.servidores=localhost:9092
```

## Publicarlo en internet con Render (para pasar un enlace)

1. Crea una cuenta en [render.com](https://render.com) entrando con GitHub.
2. **New → Web Service**, elige el repositorio `emisora_gameshakers_ws`. Render detecta el `Dockerfile` (lenguaje **Docker**). Elige la instancia **Free** y pulsa **Create Web Service**.
   (Otra opción: **New → Blueprint**, que lee `render.yaml` y crea el servicio igual.)
3. Cuando termine, Render te da un enlace como `https://gameshakers-chat.onrender.com`. Ábrelo en el navegador: debe mostrar las 3 salas.
4. Comparte ese enlace: cada persona abre `chat.jar` y escribe en **Servidor** `gameshakers-chat.onrender.com` (el cliente usa `wss://…/chat` automáticamente, conexión cifrada).

Ten en cuenta:
- En el plan gratuito, Render **apaga el servicio tras un rato sin uso** y la siguiente conexión tarda en despertarlo (alrededor de un minuto). Abre primero el enlace en el navegador y espera a que cargue.
- En Render no hay Kafka: el servidor funciona en memoria, así que el historial se pierde cuando el servicio se reinicia o se duerme.
- No hay que configurar router ni firewall: Render da la dirección pública.

## Abrir y generar los .jar en IntelliJ IDEA
`servidor/` y `chat/` son proyectos Maven independientes:
1. **File → Open** → elige la carpeta. IntelliJ descarga las librerías.
2. Ejecuta con las configuraciones incluidas: **Servidor**, **Servidor con Kafka** o **ChatApp** (esta permite abrir varias ventanas).
3. Para el `.jar`: panel **Maven** → *Lifecycle* → **package** (o `mvn package`). Queda en `target/servidor.jar` o `target/chat.jar`.

`Protocolo.java` está en los dos proyectos (`org.gameshakers.comun`): si lo cambias, cámbialo en ambos.

## Protocolo WebSocket (`/chat`, mensajes de texto, campos separados por `|`)

| Dirección | Mensaje | Significado |
|---|---|---|
| Cliente → servidor | `ENTRAR\|sala\|nombre` | Entrar al chat en una sala |
| Cliente → servidor | `CAMBIAR\|sala` | Pasarse a otra sala |
| Cliente → servidor | `CHAT\|texto` | Escribir en la sala actual |
| Servidor → cliente | `BIENVENIDO\|sala\|nombreChat` | Entraste (o te cambiaste) a la sala |
| Servidor → cliente | `RECHAZO\|motivo` | No se pudo entrar (nombre repetido, sala llena…) y se cierra |
| Servidor → cliente | `SALAS\|xbox;3\|playstation;1\|steam;2` | Personas en cada sala |
| Servidor → cliente | `USUARIOS\|sala\|Ana,Luis` | Quién está en tu sala |
| Servidor → cliente | `CHAT\|sala\|hora\|autor\|texto` | Un mensaje (también los del historial) |
| Servidor → cliente | `AVISO\|texto` | Alguien entró, salió o se cambió de sala |

Ids de sala: `xbox`, `playstation`, `steam`.

## Reglas y protecciones
- Nombres únicos en todo el servidor (máx. 24 caracteres).
- Máximo 50 personas por sala.
- Mensajes de hasta 300 caracteres y máximo 5 cada 5 segundos por persona.
- Conexiones que no entran a una sala en 15 s se cierran; clientes que no reciben a tiempo se desconectan para no frenar a los demás.
- Kafka (si se usa) escucha solo en `localhost`.

Librerías: Spring Boot 4.1.3 (Web + WebSocket), kafka-clients (opcional), FlatLaf 3.5.4 (apariencia del cliente). El cliente usa el WebSocket incluido en Java (`java.net.http`).
