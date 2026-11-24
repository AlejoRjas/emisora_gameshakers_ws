package org.gameshakers.comun;

import javax.sound.sampled.AudioFormat;
import java.awt.Color;

/**
 * Reglas que comparten el servidor, el locutor y el oyente.
 * (Este archivo es igual en los tres proyectos: si lo cambias, cámbialo en todos.)
 *
 * Locutores y oyentes se conectan al servidor por WebSocket (ws://servidor:puerto).
 * Dentro del servidor, el audio y el chat de cada sala pasan por Kafka.
 *
 * Mensajes de texto (campos separados por |, el último puede contener |):
 *
 *   Servidor -> cliente
 *     RETO|codigo|claveOyentes(1/0)        al conectarse; el código sirve para probar la contraseña
 *     BIENVENIDO|rol|sala|nombreRadio
 *     RECHAZO|motivo                        y luego se cierra la conexión
 *     SALAS|sala;locutor;oyentes;titulo;mic|...   estado de las 3 salas
 *     OYENTES|sala|locutor|nombre1,nombre2,...
 *     INFO|sala|titulo|mic(1/0)|locutor
 *     CHAT|sala|hora|autor|rol|texto
 *     AVISO|texto
 *     (binario)                            audio MP3 de la sala en la que está el oyente
 *
 *   Cliente -> servidor
 *     ENTRAR|rol|sala|nombre|prueba         prueba = HMAC-SHA256(contraseña, codigo) o vacía
 *     CAMBIAR|sala                          (oyente) pasarse a otra sala
 *     CHAT|texto
 *     INFO|titulo|mic(1/0)                  (locutor) qué está sonando
 *     (binario)                            (locutor) audio MP3 de su sala
 */
public final class Protocolo {

    public static final String NOMBRE_RADIO = "GameShakers Radio";
    public static final int PUERTO_PREDETERMINADO = 8887;
    public static final String KAFKA_PREDETERMINADO = "localhost:9092";
    public static final int MAX_OYENTES_POR_SALA = 25;

    /** Las tres salas de la radio. */
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

    public static final String LOCUTOR = "LOCUTOR";
    public static final String OYENTE = "OYENTE";

    public static final String RETO = "RETO";
    public static final String BIENVENIDO = "BIENVENIDO";
    public static final String RECHAZO = "RECHAZO";
    public static final String SALAS = "SALAS";
    public static final String OYENTES = "OYENTES";
    public static final String INFO = "INFO";
    public static final String CHAT = "CHAT";
    public static final String AVISO = "AVISO";
    public static final String ENTRAR = "ENTRAR";
    public static final String CAMBIAR = "CAMBIAR";

    public static final String SEP = "|";
    public static final String SEP_REGEX = "\\|";
    public static final int MAX_CHAT = 300;

    /** Formato del audio que el locutor mezcla y codifica a MP3. */
    public static final float FRECUENCIA = 44_100f;
    public static final int MUESTRAS_POR_TRAMA = 1152;
    public static final int KBPS = 128;
    /** Tramas MP3 por mensaje (≈52 ms de audio). */
    public static final int TRAMAS_POR_PAQUETE = 2;

    public static AudioFormat formatoPcm(int canales) {
        return new AudioFormat(FRECUENCIA, 16, canales, true, false);
    }

    /** Quita los caracteres que el protocolo usa como separadores. */
    public static String limpiarNombre(String nombre) {
        String n = nombre == null ? "" : nombre.replaceAll("[|;,\\p{Cntrl}]", " ").strip();
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    /** Admite "host", "host:puerto" o "ws://host:puerto"; devuelve la URI WebSocket. */
    public static String uriServidor(String texto) {
        String t = texto.strip();
        if (t.startsWith("ws://") || t.startsWith("wss://")) return t;
        return "ws://" + (t.contains(":") ? t : t + ":" + PUERTO_PREDETERMINADO);
    }

    private Protocolo() {
    }
}
