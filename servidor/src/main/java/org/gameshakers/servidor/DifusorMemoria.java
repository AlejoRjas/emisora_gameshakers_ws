package org.gameshakers.servidor;

import java.util.List;
import java.util.function.Consumer;

/** Reparte los mensajes directamente, en memoria. El historial se pierde al reiniciar el servidor. */
public class DifusorMemoria implements Difusor {

    private Consumer<MensajeChat> alRecibir = m -> { };

    @Override
    public void iniciar(Consumer<MensajeChat> alRecibir) {
        this.alRecibir = alRecibir;
    }

    @Override
    public void publicar(MensajeChat mensaje) {
        alRecibir.accept(mensaje);
    }

    @Override
    public List<MensajeChat> historialPrevio() {
        return List.of();
    }

    @Override
    public String descripcion() {
        return "en memoria (sin Kafka)";
    }

    @Override
    public void detener() {
    }
}
