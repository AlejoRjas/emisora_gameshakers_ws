package org.gameshakers.servidor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado de las tres salas: quién está en cada una, su historial y el reparto de mensajes.
 */
@Service
public class SalasChat {

    private static final Logger log = LoggerFactory.getLogger(SalasChat.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final long TIEMPO_PARA_ENTRAR_MS = 15_000;

    /** Lo que se sabe de cada conexión. */
    static class Usuario {
        final WebSocketSession sesion;
        final long conectadoEn = System.currentTimeMillis();
        volatile String nombre;
        volatile Sala sala;
        final Deque<Long> mensajesRecientes = new ArrayDeque<>();

        Usuario(WebSocketSession sesion) {
            this.sesion = sesion;
        }
    }

    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final Map<Sala, List<Usuario>> enSala = new EnumMap<>(Sala.class);
    private final Map<Sala, Deque<MensajeChat>> historial = new EnumMap<>(Sala.class);
    private final Difusor difusor;
    private final int maxPorSala;
    private final ZoneId zona;
    private volatile long mensajesTotales;

    public SalasChat(Difusor difusor, @Value("${chat.max-usuarios-por-sala:50}") int maxPorSala,
                     @Value("${chat.zona-horaria:America/Bogota}") String zona) {
        this.difusor = difusor;
        this.maxPorSala = maxPorSala;
        this.zona = ZoneId.of(zona);
        for (Sala s : Sala.values()) {
            enSala.put(s, new CopyOnWriteArrayList<>());
            historial.put(s, new ArrayDeque<>());
        }
    }

    @PostConstruct
    void iniciar() throws Exception {
        difusor.iniciar(this::recibido);
        for (MensajeChat m : difusor.historialPrevio()) guardar(m);
        log.info("Salas listas. Mensajes por: {}. Historial recuperado: {} mensajes.",
                difusor.descripcion(), difusor.historialPrevio().size());
    }

    @PreDestroy
    void detener() {
        difusor.detener();
    }

    // ------------------------------------------------------------------ conexiones

    public void conectado(WebSocketSession sesion) {
        usuarios.put(sesion.getId(), new Usuario(sesion));
    }

    public void desconectado(WebSocketSession sesion) {
        Usuario u = usuarios.remove(sesion.getId());
        if (u == null || u.sala == null) return;
        enSala.get(u.sala).remove(u);
        log.info("< {} salió de {}", u.nombre, u.sala.titulo);
        enviarASala(u.sala, Protocolo.AVISO + Protocolo.SEP + u.nombre + " salió de la sala.");
        cambiaronPersonas(u.sala);
    }

    public void mensaje(WebSocketSession sesion, String texto) {
        Usuario u = usuarios.get(sesion.getId());
        if (u == null) return;
        String[] p = texto.split(Protocolo.SEP_REGEX, -1);
        if (u.sala == null) {
            if (p[0].equals(Protocolo.ENTRAR) && p.length >= 3) entrar(u, Sala.porId(p[1]), p[2]);
            else cerrar(u, "Primero hay que entrar.");
            return;
        }
        switch (p[0]) {
            case Protocolo.CAMBIAR -> {
                Sala nueva = p.length > 1 ? Sala.porId(p[1]) : null;
                if (nueva != null && nueva != u.sala) cambiar(u, nueva);
            }
            case Protocolo.CHAT -> escribir(u, texto.substring(Protocolo.CHAT.length()).replaceFirst("^\\|", ""));
            default -> { /* desconocido: se ignora */ }
        }
    }

    private void entrar(Usuario u, Sala sala, String nombreCrudo) {
        String nombre = Protocolo.limpiarNombre(nombreCrudo);
        if (sala == null || nombre.isEmpty()) {
            cerrar(u, "Escribe un nombre y elige una sala.");
            return;
        }
        synchronized (this) {
            for (Usuario otro : usuarios.values()) {
                if (otro != u && otro.sala != null && nombre.equalsIgnoreCase(otro.nombre)) {
                    cerrar(u, "El nombre " + nombre + " ya está en uso. Elige otro.");
                    return;
                }
            }
            if (enSala.get(sala).size() >= maxPorSala) {
                cerrar(u, "La sala " + sala.titulo + " está llena.");
                return;
            }
            u.nombre = nombre;
            u.sala = sala;
            enSala.get(sala).add(u);
        }
        log.info("> {} entró a {}", nombre, sala.titulo);
        bienvenida(u);
        enviarASala(sala, Protocolo.AVISO + Protocolo.SEP + nombre + " entró a la sala.");
        cambiaronPersonas(sala);
    }

    private void cambiar(Usuario u, Sala nueva) {
        Sala vieja;
        synchronized (this) {
            if (enSala.get(nueva).size() >= maxPorSala) {
                enviar(u, Protocolo.AVISO + Protocolo.SEP + "La sala " + nueva.titulo + " está llena.");
                return;
            }
            vieja = u.sala;
            enSala.get(vieja).remove(u);
            u.sala = nueva;
            enSala.get(nueva).add(u);
        }
        log.info("  {} pasó de {} a {}", u.nombre, vieja.titulo, nueva.titulo);
        enviarASala(vieja, Protocolo.AVISO + Protocolo.SEP + u.nombre + " se fue a la sala " + nueva.titulo + ".");
        cambiaronPersonas(vieja);
        bienvenida(u);
        enviarASala(nueva, Protocolo.AVISO + Protocolo.SEP + u.nombre + " llegó desde la sala " + vieja.titulo + ".");
        cambiaronPersonas(nueva);
    }

    /** Confirmación, estado de las salas e historial de la sala. */
    private void bienvenida(Usuario u) {
        enviar(u, Protocolo.BIENVENIDO + Protocolo.SEP + u.sala.id + Protocolo.SEP + Protocolo.NOMBRE_CHAT);
        enviar(u, mensajeSalas());
        List<MensajeChat> previos;
        Deque<MensajeChat> h = historial.get(u.sala);
        synchronized (h) {
            previos = new ArrayList<>(h);
        }
        for (MensajeChat m : previos) enviar(u, m.comoLinea());
    }

    private void escribir(Usuario u, String crudo) {
        String txt = crudo.replaceAll("\\p{Cntrl}", " ").strip();
        if (txt.isEmpty()) return;
        if (txt.length() > Protocolo.MAX_CHAT) txt = txt.substring(0, Protocolo.MAX_CHAT);
        long ahora = System.currentTimeMillis();
        synchronized (u.mensajesRecientes) {
            while (!u.mensajesRecientes.isEmpty() && ahora - u.mensajesRecientes.peekFirst() > 5000) {
                u.mensajesRecientes.pollFirst();
            }
            if (u.mensajesRecientes.size() >= 5) {   // máximo 5 mensajes cada 5 s
                enviar(u, Protocolo.AVISO + Protocolo.SEP + "Vas muy rápido: espera unos segundos para escribir.");
                return;
            }
            u.mensajesRecientes.addLast(ahora);
        }
        // no se reparte directamente: pasa por el difusor (memoria o Kafka) y vuelve por recibido()
        difusor.publicar(new MensajeChat(u.sala, LocalTime.now(zona).format(HORA), u.nombre, txt));
    }

    /** Mensaje que entrega el difusor: se guarda en el historial y se reparte a su sala. */
    private void recibido(MensajeChat m) {
        mensajesTotales++;
        guardar(m);
        enviarASala(m.sala(), m.comoLinea());
    }

    private void guardar(MensajeChat m) {
        Deque<MensajeChat> h = historial.get(m.sala());
        synchronized (h) {
            h.addLast(m);
            while (h.size() > Protocolo.MAX_HISTORIAL) h.pollFirst();
        }
    }

    // ------------------------------------------------------------------ difusión

    private void cambiaronPersonas(Sala sala) {
        enviarASala(sala, Protocolo.USUARIOS + Protocolo.SEP + sala.id + Protocolo.SEP + String.join(",", nombres(sala)));
        enviarATodos(mensajeSalas());
    }

    private String mensajeSalas() {
        StringBuilder sb = new StringBuilder(Protocolo.SALAS);
        for (Sala s : Sala.values()) sb.append(Protocolo.SEP).append(s.id).append(';').append(enSala.get(s).size());
        return sb.toString();
    }

    public List<String> nombres(Sala sala) {
        List<String> n = new ArrayList<>();
        for (Usuario u : enSala.get(sala)) n.add(u.nombre);
        n.sort(String.CASE_INSENSITIVE_ORDER);
        return n;
    }

    public int mensajesEnHistorial(Sala sala) {
        Deque<MensajeChat> h = historial.get(sala);
        synchronized (h) {
            return h.size();
        }
    }

    public long mensajesTotales() {
        return mensajesTotales;
    }

    public String descripcionDifusor() {
        return difusor.descripcion();
    }

    private void enviarASala(Sala sala, String msg) {
        for (Usuario u : enSala.get(sala)) enviar(u, msg);
    }

    private void enviarATodos(String msg) {
        for (Usuario u : usuarios.values()) if (u.sala != null) enviar(u, msg);
    }

    private static void enviar(Usuario u, String msg) {
        try {
            if (u.sesion.isOpen()) u.sesion.sendMessage(new TextMessage(msg));
        } catch (IOException | IllegalStateException e) {
            // conexión caída o demasiado lenta: se cerrará y saldrá de la sala
        }
    }

    private void cerrar(Usuario u, String motivo) {
        enviar(u, Protocolo.RECHAZO + Protocolo.SEP + motivo);
        try {
            u.sesion.close(CloseStatus.POLICY_VIOLATION.withReason(motivo.length() > 100 ? motivo.substring(0, 100) : motivo));
        } catch (IOException ignorada) {
            // ya estaba cerrada
        }
    }

    /** Cierra conexiones que no se identificaron a tiempo. */
    @Scheduled(fixedDelay = 5000)
    void expulsarRezagados() {
        long ahora = System.currentTimeMillis();
        for (Usuario u : usuarios.values()) {
            if (u.sala == null && ahora - u.conectadoEn > TIEMPO_PARA_ENTRAR_MS) cerrar(u, "Tiempo agotado.");
        }
    }
}
