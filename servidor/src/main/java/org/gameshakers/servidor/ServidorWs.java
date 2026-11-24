package org.gameshakers.servidor;

import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;
import org.gameshakers.comun.Seguridad;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Servidor WebSocket de la radio.
 *
 * Recibe a locutores y oyentes, los ubica en una de las tres salas, publica en Kafka el audio y
 * el chat que llegan, y reparte a cada sala lo que Kafka le entrega (ver {@link PuenteKafka}).
 */
public class ServidorWs extends WebSocketServer implements PuenteKafka.Receptor {

    private static final int MAX_FALLOS = 5;
    private static final long BLOQUEO_MS = 60_000;
    private static final long TIEMPO_PARA_ENTRAR_MS = 15_000;
    /** Si un oyente acumula más mensajes sin enviar que esto (red lenta), se le saltan paquetes de audio. */
    private static final int MAX_COLA_OYENTE = 60;
    private static final int MAX_HISTORIAL_CHAT = 40;

    /** Lo que el servidor sabe de cada conexión. */
    static class Sesion {
        final String codigo;
        final long conectadoEn = System.currentTimeMillis();
        volatile boolean dentro;
        volatile String rol;
        volatile Sala sala;
        volatile String nombre = "";
        final Deque<Long> chatsRecientes = new ArrayDeque<>();

        Sesion(String codigo) {
            this.codigo = codigo;
        }
    }

    /** Estado de una sala. */
    static class EstadoSala {
        volatile WebSocket locutor;
        volatile String titulo = "";
        volatile boolean mic;
        final List<WebSocket> oyentes = new CopyOnWriteArrayList<>();
        final Deque<String> historialChat = new ArrayDeque<>();
        volatile long paquetesAudio;
    }

    private final Map<Sala, EstadoSala> salas = new EnumMap<>(Sala.class);
    private final Map<String, long[]> intentos = new ConcurrentHashMap<>();   // ip -> {fallos, bloqueadoHasta}
    private final SecureRandom azar = new SecureRandom();
    private final String claveLocutores;
    private final String claveOyentes;
    private final int maxOyentesPorSala;
    private final Consumer<String> registro;
    private final Runnable alCambiar;
    private PuenteKafka kafka;
    private Timer temporizador;
    private final java.util.concurrent.CountDownLatch arranque = new java.util.concurrent.CountDownLatch(1);
    private volatile Exception errorArranque;

    public ServidorWs(int puerto, String claveLocutores, String claveOyentes, int maxOyentesPorSala,
                      Consumer<String> registro, Runnable alCambiar) {
        // tamaño máximo de mensaje entrante: 64 KB (el audio llega en trozos de ~1 KB)
        super(new InetSocketAddress(puerto), List.of(new Draft_6455(Collections.emptyList(), 64 * 1024)));
        this.claveLocutores = claveLocutores == null ? "" : claveLocutores;
        this.claveOyentes = claveOyentes == null ? "" : claveOyentes;
        this.maxOyentesPorSala = maxOyentesPorSala;
        this.registro = registro;
        this.alCambiar = alCambiar;
        for (Sala s : Sala.values()) salas.put(s, new EstadoSala());
        setReuseAddr(true);
        setConnectionLostTimeout(15);   // ping/pong: si no responde en ~15 s se cierra
    }

    public void setKafka(PuenteKafka kafka) {
        this.kafka = kafka;
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** Espera a que el servidor quede escuchando; lanza la excepción si no pudo abrir el puerto. */
    public void esperarInicio() throws Exception {
        if (!arranque.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("El servidor no arrancó a tiempo.");
        if (errorArranque != null) {
            throw new IllegalStateException("No se pudo abrir el puerto " + getPort() + " (¿otro programa lo usa?): "
                    + errorArranque.getMessage(), errorArranque);
        }
    }

    @Override
    public void onStart() {
        arranque.countDown();
        temporizador = new Timer("salas", true);
        temporizador.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                expulsarRezagados();
                enviarATodos(mensajeSalas());
            }
        }, 2000, 2000);
    }

    public void apagar() throws InterruptedException {
        if (temporizador != null) temporizador.cancel();
        for (WebSocket c : getConnections()) c.close(1001, "El servidor se apagó");
        stop(2000);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        byte[] b = new byte[16];
        azar.nextBytes(b);
        Sesion s = new Sesion(HexFormat.of().formatHex(b));
        conn.setAttachment(s);
        conn.send(Protocolo.RETO + Protocolo.SEP + s.codigo + Protocolo.SEP + (claveOyentes.isEmpty() ? "0" : "1"));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Sesion s = conn.getAttachment();
        if (s == null || !s.dentro) return;
        EstadoSala e = salas.get(s.sala);
        if (Protocolo.LOCUTOR.equals(s.rol)) {
            if (e.locutor == conn) {
                e.locutor = null;
                e.titulo = "";
                e.mic = false;
                registro.accept("< El locutor " + s.nombre + " salió de " + s.sala.titulo);
                enviarASala(s.sala, Protocolo.AVISO + Protocolo.SEP + "El locutor " + s.nombre + " salió de la sala.");
                enviarInfo(s.sala);
            }
        } else {
            e.oyentes.remove(conn);
            registro.accept("< " + s.nombre + " salió de " + s.sala.titulo);
        }
        cambiaronPersonas(s.sala);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            if (arranque.getCount() > 0) {
                errorArranque = ex;
                arranque.countDown();
            } else {
                registro.accept("Error del servidor: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ mensajes

    @Override
    public void onMessage(WebSocket conn, String texto) {
        Sesion s = conn.getAttachment();
        if (s == null) return;
        String[] p = texto.split(Protocolo.SEP_REGEX, -1);
        if (!s.dentro) {
            if (p[0].equals(Protocolo.ENTRAR) && p.length >= 5) entrar(conn, s, p);
            else conn.close(1008, "Primero hay que entrar");
            return;
        }
        switch (p[0]) {
            case Protocolo.CAMBIAR -> {
                Sala nueva = p.length > 1 ? Sala.porId(p[1]) : null;
                if (nueva != null && Protocolo.OYENTE.equals(s.rol) && nueva != s.sala) cambiarSala(conn, s, nueva);
            }
            case Protocolo.CHAT -> {
                if (p.length < 2) return;
                String txt = texto.substring(Protocolo.CHAT.length() + 1).replaceAll("\\p{Cntrl}", " ").strip();
                if (txt.isEmpty()) return;
                if (txt.length() > Protocolo.MAX_CHAT) txt = txt.substring(0, Protocolo.MAX_CHAT);
                if (!permitirChat(s)) {
                    conn.send(Protocolo.AVISO + Protocolo.SEP + "Vas muy rápido: espera unos segundos para escribir.");
                    return;
                }
                String hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                // el chat no se reparte directamente: se publica en Kafka y se reparte al consumirlo
                kafka.publicarChat(s.sala, hora + Protocolo.SEP + s.nombre + Protocolo.SEP + s.rol + Protocolo.SEP + txt);
            }
            case Protocolo.INFO -> {
                EstadoSala e = salas.get(s.sala);
                if (Protocolo.LOCUTOR.equals(s.rol) && e.locutor == conn && p.length >= 3) {
                    e.titulo = p[1].replace(";", ",");
                    e.mic = "1".equals(p[2]);
                    enviarInfo(s.sala);
                }
            }
            default -> { /* desconocido: se ignora */ }
        }
    }

    /** Audio del locutor: se publica en el tema Kafka de su sala. */
    @Override
    public void onMessage(WebSocket conn, ByteBuffer datos) {
        Sesion s = conn.getAttachment();
        if (s == null || !s.dentro || !Protocolo.LOCUTOR.equals(s.rol)) return;
        if (salas.get(s.sala).locutor != conn) return;
        byte[] mp3 = new byte[datos.remaining()];
        datos.get(mp3);
        kafka.publicarAudio(s.sala, mp3);
    }

    /** ENTRAR|rol|sala|nombre|prueba */
    private void entrar(WebSocket conn, Sesion s, String[] p) {
        String rol = p[1];
        Sala sala = Sala.porId(p[2]);
        String nombre = Protocolo.limpiarNombre(p[3]);
        String ip = conn.getRemoteSocketAddress().getAddress().getHostAddress();
        if (sala == null || nombre.isEmpty() || !(rol.equals(Protocolo.LOCUTOR) || rol.equals(Protocolo.OYENTE))) {
            rechazar(conn, "Datos de entrada no válidos.");
            return;
        }
        long ahora = System.currentTimeMillis();
        long[] in = intentos.computeIfAbsent(ip, k -> new long[2]);
        synchronized (in) {
            if (ahora < in[1]) {
                rechazar(conn, "Demasiados intentos fallidos. Espera un minuto.");
                return;
            }
            String clave = rol.equals(Protocolo.LOCUTOR) ? claveLocutores : claveOyentes;
            if (!clave.isEmpty() && !Seguridad.iguales(p[4], Seguridad.prueba(clave, s.codigo))) {
                if (++in[0] >= MAX_FALLOS) {
                    in[0] = 0;
                    in[1] = ahora + BLOQUEO_MS;
                    registro.accept("! " + ip + " bloqueada 1 minuto por contraseñas incorrectas");
                } else {
                    registro.accept("! Contraseña incorrecta (" + rol.toLowerCase() + ") desde " + ip);
                }
                rechazar(conn, "Contraseña incorrecta.");
                return;
            }
            in[0] = 0;
        }

        EstadoSala e = salas.get(sala);
        synchronized (e) {
            if (rol.equals(Protocolo.LOCUTOR)) {
                if (e.locutor != null && e.locutor.isOpen()) {
                    rechazar(conn, "La sala " + sala.titulo + " ya tiene locutor (" + ((Sesion) e.locutor.getAttachment()).nombre + ").");
                    return;
                }
                e.locutor = conn;
            } else {
                if (e.oyentes.size() >= maxOyentesPorSala) {
                    rechazar(conn, "La sala " + sala.titulo + " está llena (" + maxOyentesPorSala + " oyentes).");
                    return;
                }
                e.oyentes.add(conn);
            }
        }
        s.rol = rol;
        s.sala = sala;
        s.nombre = nombre;
        s.dentro = true;
        conn.send(Protocolo.BIENVENIDO + Protocolo.SEP + rol + Protocolo.SEP + sala.id + Protocolo.SEP + Protocolo.NOMBRE_RADIO);
        registro.accept("> " + (rol.equals(Protocolo.LOCUTOR) ? "Locutor " : "") + nombre + " entró a " + sala.titulo + " desde " + ip);
        if (rol.equals(Protocolo.LOCUTOR)) {
            enviarASala(sala, Protocolo.AVISO + Protocolo.SEP + nombre + " es ahora el locutor de la sala.");
        }
        bienvenidaASala(conn, sala);
        cambiaronPersonas(sala);
    }

    private void cambiarSala(WebSocket conn, Sesion s, Sala nueva) {
        EstadoSala destino = salas.get(nueva);
        synchronized (destino) {
            if (destino.oyentes.size() >= maxOyentesPorSala) {
                conn.send(Protocolo.AVISO + Protocolo.SEP + "La sala " + nueva.titulo + " está llena.");
                return;
            }
            Sala vieja = s.sala;
            salas.get(vieja).oyentes.remove(conn);
            s.sala = nueva;
            destino.oyentes.add(conn);
            conn.send(Protocolo.BIENVENIDO + Protocolo.SEP + s.rol + Protocolo.SEP + nueva.id + Protocolo.SEP + Protocolo.NOMBRE_RADIO);
            registro.accept("  " + s.nombre + " pasó de " + vieja.titulo + " a " + nueva.titulo);
            cambiaronPersonas(vieja);
        }
        bienvenidaASala(conn, nueva);
        cambiaronPersonas(nueva);
    }

    /** Al entrar a una sala: estado, qué suena, quién está y los últimos mensajes del chat. */
    private void bienvenidaASala(WebSocket conn, Sala sala) {
        EstadoSala e = salas.get(sala);
        enviar(conn, mensajeSalas());
        enviar(conn, mensajeInfo(sala));
        List<String> historial;
        synchronized (e.historialChat) {
            historial = new ArrayList<>(e.historialChat);
        }
        for (String m : historial) enviar(conn, m);
    }

    private void rechazar(WebSocket conn, String motivo) {
        conn.send(Protocolo.RECHAZO + Protocolo.SEP + motivo);
        conn.close(1008, motivo);
    }

    private boolean permitirChat(Sesion s) {
        long ahora = System.currentTimeMillis();
        synchronized (s.chatsRecientes) {
            while (!s.chatsRecientes.isEmpty() && ahora - s.chatsRecientes.peekFirst() > 5000) s.chatsRecientes.pollFirst();
            if (s.chatsRecientes.size() >= 5) return false;    // máximo 5 mensajes cada 5 s
            s.chatsRecientes.addLast(ahora);
            return true;
        }
    }

    // ------------------------------------------------------------------ lo que llega de Kafka

    @Override
    public void audio(Sala sala, byte[] mp3) {
        EstadoSala e = salas.get(sala);
        e.paquetesAudio++;
        for (WebSocket o : e.oyentes) {
            if (!o.isOpen()) continue;
            // un oyente con red lenta no debe frenar a los demás ni llenar la memoria
            if (o instanceof WebSocketImpl w && w.outQueue.size() > MAX_COLA_OYENTE) continue;
            o.send(mp3);
        }
    }

    @Override
    public void chat(Sala sala, String mensaje) {
        String linea = Protocolo.CHAT + Protocolo.SEP + sala.id + Protocolo.SEP + mensaje;
        EstadoSala e = salas.get(sala);
        synchronized (e.historialChat) {
            e.historialChat.addLast(linea);
            while (e.historialChat.size() > MAX_HISTORIAL_CHAT) e.historialChat.pollFirst();
        }
        enviarASala(sala, linea);
    }

    // ------------------------------------------------------------------ difusión

    private void cambiaronPersonas(Sala sala) {
        enviarASala(sala, mensajeOyentes(sala));
        enviarATodos(mensajeSalas());
        alCambiar.run();
    }

    private void enviarInfo(Sala sala) {
        enviarASala(sala, mensajeInfo(sala));
        enviarATodos(mensajeSalas());
        alCambiar.run();
    }

    private String mensajeInfo(Sala sala) {
        EstadoSala e = salas.get(sala);
        return Protocolo.INFO + Protocolo.SEP + sala.id + Protocolo.SEP + e.titulo + Protocolo.SEP + (e.mic ? "1" : "0")
                + Protocolo.SEP + nombreLocutor(sala);
    }

    private String mensajeOyentes(Sala sala) {
        List<String> nombres = new ArrayList<>();
        for (WebSocket o : salas.get(sala).oyentes) nombres.add(((Sesion) o.getAttachment()).nombre);
        nombres.sort(String.CASE_INSENSITIVE_ORDER);
        return Protocolo.OYENTES + Protocolo.SEP + sala.id + Protocolo.SEP + nombreLocutor(sala) + Protocolo.SEP + String.join(",", nombres);
    }

    String mensajeSalas() {
        StringBuilder sb = new StringBuilder(Protocolo.SALAS);
        for (Sala s : Sala.values()) {
            EstadoSala e = salas.get(s);
            sb.append(Protocolo.SEP).append(s.id).append(';').append(nombreLocutor(s)).append(';')
                    .append(e.oyentes.size()).append(';').append(e.titulo.replace(Protocolo.SEP, "/")).append(';')
                    .append(e.mic ? "1" : "0");
        }
        return sb.toString();
    }

    public String nombreLocutor(Sala sala) {
        WebSocket l = salas.get(sala).locutor;
        return l == null ? "" : ((Sesion) l.getAttachment()).nombre;
    }

    public List<String> oyentes(Sala sala) {
        List<String> nombres = new ArrayList<>();
        for (WebSocket o : salas.get(sala).oyentes) nombres.add(((Sesion) o.getAttachment()).nombre);
        return nombres;
    }

    public String titulo(Sala sala) {
        return salas.get(sala).titulo;
    }

    public boolean micAbierto(Sala sala) {
        return salas.get(sala).mic;
    }

    public long paquetesAudio(Sala sala) {
        return salas.get(sala).paquetesAudio;
    }

    private void enviarASala(Sala sala, String msg) {
        EstadoSala e = salas.get(sala);
        enviar(e.locutor, msg);
        for (WebSocket o : e.oyentes) enviar(o, msg);
    }

    private void enviarATodos(String msg) {
        for (WebSocket c : getConnections()) {
            Sesion s = c.getAttachment();
            if (s != null && s.dentro) enviar(c, msg);
        }
    }

    private static void enviar(WebSocket c, String msg) {
        if (c != null && c.isOpen()) c.send(msg);
    }

    /** Cierra conexiones que no se identificaron a tiempo y limpia bloqueos vencidos. */
    private void expulsarRezagados() {
        long ahora = System.currentTimeMillis();
        for (WebSocket c : getConnections()) {
            Sesion s = c.getAttachment();
            if (s != null && !s.dentro && ahora - s.conectadoEn > TIEMPO_PARA_ENTRAR_MS) c.close(1008, "Tiempo agotado");
        }
        intentos.values().removeIf(in -> in[0] == 0 && ahora > in[1]);
    }
}
