package org.gameshakers.servidor;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Punto de entrada WebSocket (/chat): pasa cada evento a {@link SalasChat}. */
@Component
public class ChatHandler extends TextWebSocketHandler {

    private final SalasChat salas;

    public ChatHandler(SalasChat salas) {
        this.salas = salas;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        // permite enviar desde varios hilos a la vez; si un cliente no recibe en 5 s o acumula 256 KB, se cierra
        salas.conectado(new ConcurrentWebSocketSessionDecorator(sesion, 5000, 256 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession sesion, TextMessage mensaje) {
        salas.mensaje(sesion, mensaje.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        salas.desconectado(sesion);
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        salas.desconectado(sesion);
    }
}
