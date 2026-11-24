package org.gameshakers.servidor;

import java.util.List;
import java.util.function.Consumer;

/**
 * Por dónde pasan los mensajes antes de repartirse a la sala.
 * {@link DifusorMemoria}: directo, sin nada más que instalar (lo que se usa en Render).
 * {@link DifusorKafka}: se publican en Kafka y se reparten al consumirlos.
 */
public interface Difusor {

    /** Empieza a funcionar; cada mensaje que "llegue" se entrega a {@code alRecibir}. */
    void iniciar(Consumer<MensajeChat> alRecibir) throws Exception;

    void publicar(MensajeChat mensaje);

    /** Mensajes anteriores para armar el historial de cada sala al arrancar. */
    List<MensajeChat> historialPrevio();

    String descripcion();

    void detener();
}
