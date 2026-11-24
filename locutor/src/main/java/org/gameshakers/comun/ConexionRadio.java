package org.gameshakers.comun;

import org.gameshakers.comun.Protocolo.Sala;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Conexión WebSocket con el servidor de la radio (usada por el locutor y por el oyente).
 * Se encarga del saludo (RETO -> ENTRAR) y entrega el resto de mensajes a {@link Eventos}.
 */
public class ConexionRadio extends WebSocketClient {

    public interface Eventos {
        void dentro(Sala sala);

        void salas(List<InfoSala> salas);

        void oyentes(Sala sala, String locutor, List<String> oyentes);

        void info(Sala sala, String titulo, boolean mic, String locutor);

        void chat(Sala sala, String hora, String autor, boolean esLocutor, String texto);

        void aviso(String texto);

        /** Audio MP3 (solo lo recibe el oyente). */
        default void audio(ByteBuffer mp3) {
        }

        /** La conexión terminó. {@code motivo} es null si la cerró el propio usuario. */
        void cerrada(String motivo);
    }

    /** Estado de una sala según el mensaje SALAS. */
    public record InfoSala(Sala sala, String locutor, int oyentes, String titulo, boolean mic) {
    }

    private final String rol;
    private final String nombre;
    private final String clave;
    private final Eventos eventos;
    private volatile Sala sala;
    private volatile boolean dentro;
    private volatile String motivoRechazo;
    private volatile boolean cerradaPorUsuario;

    public ConexionRadio(String direccion, String rol, Sala sala, String nombre, String clave, Eventos eventos) {
        super(URI.create(Protocolo.uriServidor(direccion)));
        this.rol = rol;
        this.sala = sala;
        this.nombre = Protocolo.limpiarNombre(nombre);
        this.clave = clave == null ? "" : clave;
        this.eventos = eventos;
        setConnectionLostTimeout(15);
    }

    public boolean estaDentro() {
        return dentro && isOpen();
    }

    public Sala sala() {
        return sala;
    }

    public void salir() {
        cerradaPorUsuario = true;
        close();
    }

    public void cambiarSala(Sala nueva) {
        if (estaDentro()) send(Protocolo.CAMBIAR + Protocolo.SEP + nueva.id);
    }

    public void enviarChat(String texto) {
        if (estaDentro()) send(Protocolo.CHAT + Protocolo.SEP + texto);
    }

    public void enviarInfo(String titulo, boolean mic) {
        if (estaDentro()) send(Protocolo.INFO + Protocolo.SEP + titulo.replace(Protocolo.SEP, "/") + Protocolo.SEP + (mic ? "1" : "0"));
    }

    public void enviarAudio(byte[] mp3) {
        if (estaDentro()) send(mp3);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        // el servidor envía primero RETO
    }

    @Override
    public void onMessage(String texto) {
        String[] p = texto.split(Protocolo.SEP_REGEX, -1);
        switch (p[0]) {
            case Protocolo.RETO -> {
                if (p.length < 3) return;
                if (Protocolo.OYENTE.equals(rol) && "1".equals(p[2]) && clave.isEmpty()) {
                    motivoRechazo = "Este servidor pide contraseña para los oyentes.";
                    close();
                    return;
                }
                send(Protocolo.ENTRAR + Protocolo.SEP + rol + Protocolo.SEP + sala.id + Protocolo.SEP + nombre
                        + Protocolo.SEP + Seguridad.prueba(clave, p[1]));
            }
            case Protocolo.BIENVENIDO -> {
                Sala s = p.length > 2 ? Sala.porId(p[2]) : null;
                if (s != null) {
                    sala = s;
                    dentro = true;
                    eventos.dentro(s);
                }
            }
            case Protocolo.RECHAZO -> motivoRechazo = p.length > 1 ? p[1] : "El servidor rechazó la conexión.";
            case Protocolo.SALAS -> {
                List<InfoSala> lista = new ArrayList<>();
                for (int i = 1; i < p.length; i++) {
                    String[] c = p[i].split(";", -1);
                    Sala s = Sala.porId(c[0]);
                    if (s == null || c.length < 5) continue;
                    lista.add(new InfoSala(s, c[1], entero(c[2]), c[3], "1".equals(c[4])));
                }
                eventos.salas(lista);
            }
            case Protocolo.OYENTES -> {
                Sala s = p.length > 3 ? Sala.porId(p[1]) : null;
                if (s == null) return;
                List<String> nombres = new ArrayList<>();
                for (String n : p[3].split(",")) if (!n.isBlank()) nombres.add(n);
                eventos.oyentes(s, p[2], nombres);
            }
            case Protocolo.INFO -> {
                Sala s = p.length > 4 ? Sala.porId(p[1]) : null;
                if (s != null) eventos.info(s, p[2], "1".equals(p[3]), p[4]);
            }
            case Protocolo.CHAT -> {
                // CHAT|sala|hora|autor|rol|texto (el texto puede contener |)
                String[] c = texto.split(Protocolo.SEP_REGEX, 6);
                Sala s = c.length == 6 ? Sala.porId(c[1]) : null;
                if (s != null) eventos.chat(s, c[2], c[3], Protocolo.LOCUTOR.equals(c[4]), c[5]);
            }
            case Protocolo.AVISO -> eventos.aviso(texto.substring(Protocolo.AVISO.length() + 1));
            default -> { }
        }
    }

    @Override
    public void onMessage(ByteBuffer datos) {
        eventos.audio(datos);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        boolean estabaDentro = dentro;
        dentro = false;
        String motivo;
        if (cerradaPorUsuario) motivo = null;
        else if (motivoRechazo != null) motivo = motivoRechazo;
        else if (!estabaDentro) motivo = "No se pudo conectar con " + getURI().getHost() + ":" + puerto()
                + ". Revisa la dirección, que el servidor esté encendido y el firewall.";
        else if (code == 1001) motivo = "El servidor se apagó.";
        else motivo = "Se perdió la conexión con el servidor.";
        eventos.cerrada(motivo);
    }

    @Override
    public void onError(Exception ex) {
        // onClose se llama a continuación con el motivo
    }

    private int puerto() {
        return getURI().getPort() < 0 ? Protocolo.PUERTO_PREDETERMINADO : getURI().getPort();
    }

    private static int entero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
