package org.gameshakers.servidor;

import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;

/** Un mensaje escrito en una sala. */
public record MensajeChat(Sala sala, String hora, String autor, String texto) {

    /** Línea del protocolo que se envía a los clientes: CHAT|sala|hora|autor|texto */
    public String comoLinea() {
        return Protocolo.CHAT + Protocolo.SEP + sala.id + Protocolo.SEP + hora + Protocolo.SEP + autor + Protocolo.SEP + texto;
    }

    /** Valor que se guarda en Kafka (la sala va como clave): hora|autor|texto */
    public String comoValorKafka() {
        return hora + Protocolo.SEP + autor + Protocolo.SEP + texto;
    }

    public static MensajeChat desdeKafka(String clave, String valor) {
        Sala sala = Sala.porId(clave);
        String[] p = valor == null ? new String[0] : valor.split(Protocolo.SEP_REGEX, 3);
        if (sala == null || p.length < 3) return null;
        return new MensajeChat(sala, p[0], p[1], p[2]);
    }
}
