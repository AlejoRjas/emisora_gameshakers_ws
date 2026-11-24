package org.gameshakers.servidor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class Configuracion implements WebSocketConfigurer {

    private final ChatHandler chatHandler;

    public Configuracion(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registro) {
        // cualquier cliente puede conectarse (el cliente de escritorio no envía un origen web)
        registro.addHandler(chatHandler, "/chat").setAllowedOrigins("*");
    }

    /** Límite de tamaño de los mensajes que llegan (un mensaje de chat válido es mucho menor). */
    @Bean
    public ServletServerContainerFactoryBean contenedorWebSocket() {
        ServletServerContainerFactoryBean f = new ServletServerContainerFactoryBean();
        f.setMaxTextMessageBufferSize(8 * 1024);
        f.setMaxBinaryMessageBufferSize(1024);
        f.setMaxSessionIdleTimeout(10L * 60 * 1000);   // 10 min sin actividad (el cliente envía ping cada 25 s)
        return f;
    }
}
