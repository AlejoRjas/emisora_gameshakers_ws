package org.gameshakers.servidor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracionDifusor {

    /** Memoria (por defecto) o Kafka si se indica chat.kafka.servidores. */
    @Bean(destroyMethod = "")
    public Difusor difusor(@Value("${chat.kafka.servidores:}") String kafka) {
        return kafka == null || kafka.isBlank() ? new DifusorMemoria() : new DifusorKafka(kafka.strip());
    }
}
