package org.gameshakers.servidor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Servidor de GameShakers Chat: tres salas (Xbox, PlayStation, PC · Steam) por WebSocket.
 *
 * Ejecutar: java -jar servidor.jar
 *   - WebSocket:        ws://localhost:8080/chat
 *   - Estado de salas:  http://localhost:8080/
 * Con Kafka (opcional): java -jar servidor.jar --chat.kafka.servidores=localhost:9092
 */
@SpringBootApplication
@EnableScheduling
public class ChatServidorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServidorApplication.class, args);
    }
}
