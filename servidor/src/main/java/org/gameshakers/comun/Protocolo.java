package org.gameshakers.comun;

import java.awt.Color;

/**
 * Reglas que comparten el servidor y el cliente de chat.
 * (Este archivo es igual en los dos proyectos: si lo cambias, cámbialo en ambos.)
 *
 * Los clientes se conectan al servidor por WebSocket (ws://servidor:puerto).
 * Dentro del servidor, todos los mensajes de chat pasan por Kafka (tema {@link #TEMA_KAFKA}).
 *
 * Mensajes de texto (campos separados por |; el último puede contener |):
 *
 *   Cliente -> servidor
 *     ENTRAR|sala|nombre          entrar al chat en una sala
 *     CAMBIAR|sala                pasarse a otra sala
 *     CHAT|texto                  escribir en la sala actual
 *
 *   Servidor -> cliente
 *     BIENVENIDO|sala|nombreChat
 *     RECHAZO|motivo              y luego se cierra la conexión
 *     SALAS|sala;usuarios|...     cuántas personas hay en cada sala
 *     USUARIOS|sala|n1,n2,...     quién está en tu sala
 *     CHAT|sala|hora|autor|texto  un mensaje (también los del historial al entrar)
 *     AVISO|texto                 alguien entró o salió, etc.
 */
public final class Protocolo {

    public static final String NOMBRE_CHAT = "GameShakers Chat";
    public static final int PUERTO_PREDETERMINADO = 8080;
    public static final String RUTA_WEBSOCKET = "/chat";
    public static final String KAFKA_PREDETERMINADO = "localhost:9092";
    public static final String TEMA_KAFKA = "gameshakers.chat";
    public static final int MAX_USUARIOS_POR_SALA = 50;
    public static final int MAX_CHAT = 300;
    public static final int MAX_HISTORIAL = 50;

    /** Las tres salas de chat. */
    public enum Sala {
        XBOX("xbox", "Xbox", new Color(0x10, 0x7C, 0x10)),
        PLAYSTATION("playstation", "PlayStation", new Color(0x00, 0x6F, 0xCD)),
        STEAM("steam", "PC · Steam", new Color(0x2A, 0x47, 0x5E));

        public final String id;
        public final String titulo;
        public final Color color;

        Sala(String id, String titulo, Color color) {
            this.id = id;
            this.titulo = titulo;
            this.color = color;
        }

        public static Sala porId(String id) {
            for (Sala s : values()) if (s.id.equalsIgnoreCase(id)) return s;
            return null;
        }

        @Override
        public String toString() {
            return titulo;
        }
    }

    public static final String ENTRAR = "ENTRAR";
    public static final String CAMBIAR = "CAMBIAR";
    public static final String CHAT = "CHAT";
    public static final String BIENVENIDO = "BIENVENIDO";
    public static final String RECHAZO = "RECHAZO";
    public static final String SALAS = "SALAS";
    public static final String USUARIOS = "USUARIOS";
    public static final String AVISO = "AVISO";

    public static final String SEP = "|";
    public static final String SEP_REGEX = "\\|";

    /** Quita los caracteres que el protocolo usa como separadores. */
    public static String limpiarNombre(String nombre) {
        String n = nombre == null ? "" : nombre.replaceAll("[|;,\\p{Cntrl}]", " ").strip();
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    /**
     * Convierte lo que escribe el usuario en la dirección WebSocket del servidor:
     *   "localhost"                      -> ws://localhost:8080/chat
     *   "192.168.1.20:8080"              -> ws://192.168.1.20:8080/chat
     *   "miapp.onrender.com"             -> wss://miapp.onrender.com/chat   (dominio en internet: conexión cifrada)
     *   "https://miapp.onrender.com"     -> wss://miapp.onrender.com/chat
     *   "ws://..." o "wss://..."         -> se usa tal cual (agregando /chat si no trae ruta)
     */
    public static String uriServidor(String texto) {
        String t = texto.strip();
        if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        String esquema;
        if (t.startsWith("ws://") || t.startsWith("wss://")) {
            esquema = t.substring(0, t.indexOf("://"));
            t = t.substring(t.indexOf("://") + 3);
        } else if (t.startsWith("https://")) {
            esquema = "wss";
            t = t.substring(8);
        } else if (t.startsWith("http://")) {
            esquema = "ws";
            t = t.substring(7);
        } else {
            String host = t.contains("/") ? t.substring(0, t.indexOf('/')) : t;
            boolean local = host.contains(":") || host.startsWith("localhost") || host.matches("[0-9.]+");
            esquema = local ? "ws" : "wss";
            if (local && !host.contains(":")) t = host + ":" + PUERTO_PREDETERMINADO + t.substring(host.length());
        }
        if (!t.contains("/")) t = t + RUTA_WEBSOCKET;
        return esquema + "://" + t;
    }

    private Protocolo() {
    }
}
