# GameShakers Radio · WebSocket + Kafka

Radio por internet con **tres salas**: **Xbox**, **PlayStation** y **PC · Steam**.
Cada sala tiene su propio locutor (micrófono + música MP3), su chat y su lista de oyentes; los oyentes eligen sala y pueden cambiarse cuando quieran.

| Carpeta | Programa | Quién lo usa | Genera |
|---|---|---|---|
| [`servidor/`](servidor) | Servidor WebSocket + Kafka | El equipo central (junto a Kafka) | `servidor.jar` |
| [`locutor/`](locutor) | Locutor (cliente WebSocket) | Quien transmite en una sala | `locutor.jar` |
| [`oyente/`](oyente) | Oyente (cliente WebSocket) | Quien escucha | `oyente.jar` |
| [`kafka/`](kafka) | Configuración y scripts para iniciar Kafka | El equipo del servidor | — |

## Arquitectura

```
 LOCUTOR Xbox ─┐                                  ┌──────────── KAFKA (localhost:9092) ────────────┐
 LOCUTOR PS  ──┤ WebSocket                        │  radio.xbox.audio    radio.playstation.audio   │
 LOCUTOR PC  ──┤ (audio MP3 binario + texto) ──►  │  radio.steam.audio   radio.chat                │
               │                      SERVIDOR ───┤      ▲ productor             │ consumidor      │
 OYENTES ◄─────┘ WebSocket            (Java)      └──────┼───────────────────────┼─────────────────┘
   (eligen sala)  ◄── audio y chat de SU sala ───────────┘◄──────────────────────┘
```

1. El **locutor** mezcla su micrófono con la canción, lo codifica a **MP3 (128 kbps)** y lo envía por **WebSocket** al servidor en trozos de ~52 ms.
2. El **servidor** publica cada trozo en el tema Kafka de su sala (`radio.<sala>.audio`). Los mensajes de chat van al tema `radio.chat` con la sala como clave.
3. Un **consumidor Kafka** del servidor lee esos temas y reparte cada registro, por WebSocket, solo a quienes están en esa sala.
4. El **oyente** guarda un pequeño buffer, decodifica el MP3 y lo reproduce.

Requisitos cumplidos: interfaz estética (tema oscuro, ecualizador y vúmetros con el audio real), micrófono del locutor, reproducción simultánea en todos los oyentes de la sala, audio solo en MP3 (la lista solo acepta MP3 válidos y lo que viaja es MP3), 3 salas, chat y lista de oyentes por sala, contraseñas.

## Puesta en marcha

Todos los equipos necesitan **Java 17 o superior** (por ejemplo [Eclipse Temurin](https://adoptium.net/)).

1. **Equipo del servidor**: inicia Kafka → ver [`kafka/LEEME.md`](kafka/LEEME.md).
2. **Equipo del servidor**: abre `servidor.jar`, pon una **clave de locutores** (y si quieres una de oyentes) y pulsa **Encender servidor**. Muestra la dirección a la que se conectan los demás (p. ej. `192.168.1.20:8887`).
3. **Locutores**: abren `locutor.jar`, escriben esa dirección, eligen sala, su nombre y la clave de locutores → **Salir al aire**. Agregan MP3 y/o abren el micrófono.
4. **Oyentes**: abren `oyente.jar`, escriben la dirección y su nombre, hacen clic en una sala → **Conectar**. Para cambiar de sala, clic en otra.

```bash
java -jar servidor.jar
java -jar locutor.jar 192.168.1.20:8887      # la dirección es opcional
java -jar oyente.jar  192.168.1.20:8887
```

### Desde otros lugares (internet)
Solo el **equipo del servidor** necesita configuración:
- Redirigir el puerto **TCP 8887** (el de WebSocket) en su router hacia ese equipo, y permitir Java en el Firewall de Windows:
  `netsh advfirewall firewall add rule name="GameShakers Radio" dir=in action=allow protocol=TCP localport=8887`
- Compartir la **IP pública** (botón *Consultar* en el servidor). Locutores y oyentes escriben `IP-pública:8887`; los oyentes eligen **Red: Internet** (buffer mayor).
- **Kafka no se expone**: escucha solo en `localhost`.
- Si el proveedor de internet usa **CGNAT**, la redirección no funcionará; alternativa: una VPN como Tailscale o ZeroTier.
- Ancho de banda de subida del servidor: ~135 kbps por oyente (128 kbps de audio más cabeceras; 10 oyentes ≈ 1,35 Mbps).

## Abrir y generar los .jar en IntelliJ IDEA
Cada carpeta (`servidor`, `locutor`, `oyente`) es un proyecto Maven independiente:
1. **File → Open** → elegir la carpeta. IntelliJ descarga las librerías.
2. Ejecutar con la configuración incluida (`ServidorApp`, `LocutorApp`, `OyenteApp`; la del oyente permite varias ventanas).
3. Generar el `.jar`: panel **Maven** → *Lifecycle* → **package** (o `mvn package`). Queda en `target/`.

El código común (`org.gameshakers.comun`) está copiado en cada proyecto: si cambias `Protocolo.java`, `Seguridad.java` o `ConexionRadio.java`, cámbialos en todos.

## Protocolo WebSocket (`ws://servidor:8887`)

| Dirección | Mensaje | Significado |
|---|---|---|
| Servidor → cliente | `RETO\|código\|claveOyentes` | Al conectarse: código para probar la contraseña (1 = los oyentes necesitan clave) |
| Cliente → servidor | `ENTRAR\|rol\|sala\|nombre\|prueba` | rol `LOCUTOR`/`OYENTE`; prueba = HMAC-SHA256(clave, código) |
| Servidor → cliente | `BIENVENIDO\|rol\|sala\|radio` / `RECHAZO\|motivo` | Resultado |
| Oyente → servidor | `CAMBIAR\|sala` | Pasarse a otra sala |
| Cliente → servidor | `CHAT\|texto` | Mensaje (pasa por Kafka `radio.chat`) |
| Locutor → servidor | `INFO\|título\|mic` | Qué suena y si el micrófono está abierto |
| Locutor → servidor | *binario* | Audio MP3 (va a Kafka `radio.<sala>.audio`) |
| Servidor → cliente | `SALAS\|…` · `OYENTES\|…` · `INFO\|…` · `CHAT\|…` · `AVISO\|…` | Estado de las salas, personas, qué suena, chat, avisos |
| Servidor → oyente | *binario* | Audio MP3 de su sala |

Ids de sala: `xbox`, `playstation`, `steam`.

## Seguridad
- **Contraseñas** de locutores y de oyentes (opcional); nunca viajan: se envía un HMAC del código que manda el servidor.
- **5 claves incorrectas** desde una IP → bloqueada 1 minuto.
- **Un locutor por sala**: si la sala ya tiene, se rechaza al segundo.
- **Máximo de oyentes por sala** (25 por omisión).
- **Chat**: máximo 300 caracteres y 5 mensajes cada 5 s por persona.
- Mensajes de más de 64 KB se rechazan; conexiones que no se identifican en 15 s se cierran.
- Kafka solo escucha en `localhost`.
- Limitación: la conexión es `ws://` (sin cifrar). Para cifrarla haría falta `wss://` con un certificado TLS.

Librerías: [Java-WebSocket 1.6.0](https://github.com/TooTallNate/Java-WebSocket), kafka-clients 4.4.0, JLayer 1.0.1 (decodificar MP3), jump3r 1.0.5 (codificar MP3), FlatLaf 3.5.4 (apariencia), slf4j-nop.
