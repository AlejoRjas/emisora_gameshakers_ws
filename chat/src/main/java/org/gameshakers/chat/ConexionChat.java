package org.gameshakers.chat;

import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Conexión WebSocket con el servidor de chat, usando el cliente WebSocket que trae Java (java.net.http).
 * Funciona con ws:// (red local) y wss:// (servidor en internet, p. ej. Render).
 */
public class ConexionChat implements WebSocket.Listener {

    public interface Eventos {
        void dentro(Sala sala);

        void salas(Map<Sala, Integer> personasPorSala);

        void usuarios(Sala sala, List<String> nombres);

        void mensaje(Sala sala, String hora, String autor, String texto);

        void aviso(String texto);

        /** La conexión terminó. {@code motivo} es null si la cerró el propio usuario. */
        void cerrada(String motivo);
    }

    private final URI uri;
    private final String nombre;
    private final Sala salaInicial;
    private final Eventos eventos;
    private final ExecutorService envios = Executors.newSingleThreadExecutor(r -> hilo(r, "chat-envios"));
    private final ScheduledExecutorService latido = Executors.newSingleThreadScheduledExecutor(r -> hilo(r, "chat-ping"));
    private final StringBuilder parcial = new StringBuilder();
    private volatile WebSocket ws;
    private volatile Sala sala;
    private volatile String motivoRechazo;
    private volatile boolean porUsuario;
    private volatile boolean terminada;

    public ConexionChat(String direccion, String nombre, Sala salaInicial, Eventos eventos) {
        this.uri = URI.create(Protocolo.uriServidor(direccion));
        this.nombre = Protocolo.limpiarNombre(nombre);
        this.salaInicial = salaInicial;
        this.eventos = eventos;
    }

    public URI uri() {
        return uri;
    }

    public Sala sala() {
        return sala;
    }

    public void conectar() {
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(uri, this)
                .whenComplete((w, error) -> {
                    if (error != null) {
                        terminar("No se pudo conectar con " + uri + ". Revisa la dirección y que el servidor esté encendido"
                                + " (en Render, la primera conexión puede tardar ~1 minuto mientras el servidor despierta).");
                    } else {
                        ws = w;
                        enviar(Protocolo.ENTRAR + Protocolo.SEP + salaInicial.id + Protocolo.SEP + nombre);
                        // ping periódico: mantiene viva la conexión a través de proxies y routers
                        latido.scheduleAtFixedRate(() -> {
                            WebSocket x = ws;
                            if (x != null && !x.isOutputClosed()) envios.execute(() -> x.sendPing(ByteBuffer.allocate(0)).join());
                        }, 25, 25, TimeUnit.SECONDS);
                    }
                });
    }

    public void cambiarSala(Sala nueva) {
        enviar(Protocolo.CAMBIAR + Protocolo.SEP + nueva.id);
    }

    public void escribir(String texto) {
        enviar(Protocolo.CHAT + Protocolo.SEP + texto);
    }

    public void salir() {
        porUsuario = true;
        WebSocket w = ws;
        if (w != null) envios.execute(() -> {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "chao").get(2, TimeUnit.SECONDS);
            } catch (Exception ignorada) {
                // si no se pudo cerrar con calma, se corta
            }
            w.abort();
        });
        terminar(null);
    }

    /** Los envíos se hacen de a uno (la API de Java no permite enviar dos a la vez). */
    private void enviar(String texto) {
        WebSocket w = ws;
        if (w == null || terminada) return;
        envios.execute(() -> {
            try {
                w.sendText(texto, true).join();
            } catch (CompletionException e) {
                // la conexión se cayó; onClose/onError avisarán
            }
        });
    }

    // ------------------------------------------------------------------ WebSocket.Listener

    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence datos, boolean ultimo) {
        parcial.append(datos);
        if (ultimo) {
            String texto = parcial.toString();
            parcial.setLength(0);
            procesar(texto);
        }
        w.request(1);
        return null;
    }

    private void procesar(String texto) {
        String[] p = texto.split(Protocolo.SEP_REGEX, -1);
        switch (p[0]) {
            case Protocolo.BIENVENIDO -> {
                Sala s = p.length > 1 ? Sala.porId(p[1]) : null;
                if (s != null) {
                    sala = s;
                    eventos.dentro(s);
                }
            }
            case Protocolo.RECHAZO -> motivoRechazo = p.length > 1 ? p[1] : "El servidor rechazó la conexión.";
            case Protocolo.SALAS -> {
                Map<Sala, Integer> m = new EnumMap<>(Sala.class);
                for (int i = 1; i < p.length; i++) {
                    String[] c = p[i].split(";");
                    Sala s = Sala.porId(c[0]);
                    if (s != null && c.length > 1) m.put(s, entero(c[1]));
                }
                eventos.salas(m);
            }
            case Protocolo.USUARIOS -> {
                Sala s = p.length > 2 ? Sala.porId(p[1]) : null;
                if (s == null) return;
                List<String> nombres = new ArrayList<>();
                for (String n : p[2].split(",")) if (!n.isBlank()) nombres.add(n);
                eventos.usuarios(s, nombres);
            }
            case Protocolo.CHAT -> {
                String[] c = texto.split(Protocolo.SEP_REGEX, 5);   // CHAT|sala|hora|autor|texto (el texto puede tener |)
                Sala s = c.length == 5 ? Sala.porId(c[1]) : null;
                if (s != null) eventos.mensaje(s, c[2], c[3], c[4]);
            }
            case Protocolo.AVISO -> eventos.aviso(texto.substring(Protocolo.AVISO.length() + 1));
            default -> { }
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int codigo, String razon) {
        if (porUsuario) terminar(null);
        else if (motivoRechazo != null) terminar(motivoRechazo);
        else terminar("Se perdió la conexión con el servidor" + (razon == null || razon.isBlank() ? "." : ": " + razon));
        return null;
    }

    @Override
    public void onError(WebSocket w, Throwable error) {
        terminar(porUsuario ? null : motivoRechazo != null ? motivoRechazo : "Se perdió la conexión con el servidor.");
    }

    private synchronized void terminar(String motivo) {
        if (terminada) return;
        terminada = true;
        latido.shutdownNow();
        envios.shutdown();
        eventos.cerrada(motivo);
    }

    private static Thread hilo(Runnable r, String nombre) {
        Thread t = new Thread(r, nombre);
        t.setDaemon(true);
        return t;
    }

    private static int entero(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
