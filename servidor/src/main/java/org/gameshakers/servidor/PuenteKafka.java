package org.gameshakers.servidor;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.gameshakers.comun.Protocolo.Sala;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Todo lo que el servidor hace con Kafka.
 *
 * Temas (topics):
 *   radio.xbox.audio, radio.playstation.audio, radio.steam.audio   audio MP3 de cada sala
 *   radio.chat                                                      chat de las 3 salas (clave = id de la sala)
 *
 * El locutor manda su audio al servidor por WebSocket; el servidor lo publica en el tema de su sala.
 * Un consumidor lee los cuatro temas y entrega cada registro al servidor WebSocket, que lo reparte
 * a los oyentes de la sala. Así Kafka es el canal por el que pasa todo el contenido de las salas.
 */
public class PuenteKafka {

    public static final String TEMA_CHAT = "radio.chat";

    /** Lo que llega desde Kafka. */
    public interface Receptor {
        void audio(Sala sala, byte[] mp3);

        void chat(Sala sala, String mensaje);
    }

    private final String servidores;
    private final Receptor receptor;
    private KafkaProducer<String, byte[]> productor;
    private KafkaConsumer<String, byte[]> consumidor;
    private Thread hiloConsumo;
    private volatile boolean activo;
    private volatile long registrosEnviados, registrosRecibidos;

    public PuenteKafka(String servidores, Receptor receptor) {
        this.servidores = servidores;
        this.receptor = receptor;
    }

    public static String temaAudio(Sala sala) {
        return "radio." + sala.id + ".audio";
    }

    /** Comprueba la conexión, crea los temas si no existen y empieza a consumir. */
    public void iniciar() throws Exception {
        Properties pa = new Properties();
        pa.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        pa.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        pa.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        try (Admin admin = Admin.create(pa)) {
            try {
                admin.describeCluster().clusterId().get(8, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                throw new IllegalStateException("No se pudo conectar a Kafka en " + servidores
                        + ". ¿Está encendido? (ver kafka/LEEME.md)", e);
            }
            Set<String> existentes = admin.listTopics().names().get(8, TimeUnit.SECONDS);
            List<NewTopic> nuevos = new ArrayList<>();
            for (Sala s : Sala.values()) {
                if (!existentes.contains(temaAudio(s))) {
                    // audio en vivo: se guarda 30 min (segmentos de 10 min) y luego Kafka lo borra
                    nuevos.add(new NewTopic(temaAudio(s), 1, (short) 1).configs(Map.of(
                            "retention.ms", "1800000", "segment.ms", "600000")));
                }
            }
            if (!existentes.contains(TEMA_CHAT)) {
                nuevos.add(new NewTopic(TEMA_CHAT, 1, (short) 1).configs(Map.of("retention.ms", "86400000")));
            }
            if (!nuevos.isEmpty()) {
                try {
                    admin.createTopics(nuevos).all().get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) throw e;
                }
            }
        }

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        pp.put(ProducerConfig.ACKS_CONFIG, "1");
        pp.put(ProducerConfig.LINGER_MS_CONFIG, 0);              // audio en vivo: enviar de inmediato
        pp.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        pp.put(ProducerConfig.CLIENT_ID_CONFIG, "radio-servidor");
        productor = new KafkaProducer<>(pp);

        Properties pc = new Properties();
        pc.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        // grupo propio en cada arranque: solo interesa lo que se publique desde ahora (en vivo)
        pc.put(ConsumerConfig.GROUP_ID_CONFIG, "radio-servidor-" + UUID.randomUUID());
        pc.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        pc.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        pc.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        pc.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        pc.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 10);      // entregar en cuanto haya datos
        consumidor = new KafkaConsumer<>(pc);
        List<String> temas = new ArrayList<>();
        for (Sala s : Sala.values()) temas.add(temaAudio(s));
        temas.add(TEMA_CHAT);
        consumidor.subscribe(temas);

        activo = true;
        hiloConsumo = new Thread(this::consumir, "kafka-consumidor");
        hiloConsumo.setDaemon(true);
        hiloConsumo.start();
        esperarAsignacion();
    }

    /** Espera a que el consumidor tenga sus particiones, para no perder lo primero que se publique. */
    private void esperarAsignacion() throws InterruptedException {
        long limite = System.currentTimeMillis() + 15_000;
        while (!listo && System.currentTimeMillis() < limite) Thread.sleep(50);
    }

    private volatile boolean listo;

    private void consumir() {
        try {
            while (activo) {
                for (ConsumerRecord<String, byte[]> r : consumidor.poll(Duration.ofMillis(100))) {
                    registrosRecibidos++;
                    if (r.topic().equals(TEMA_CHAT)) {
                        Sala sala = Sala.porId(r.key());
                        if (sala != null) receptor.chat(sala, new String(r.value(), StandardCharsets.UTF_8));
                    } else {
                        for (Sala s : Sala.values()) {
                            if (r.topic().equals(temaAudio(s))) receptor.audio(s, r.value());
                        }
                    }
                }
                if (!listo && consumidor.assignment().size() == Sala.values().length + 1) {
                    // posicionarse en el final ahora mismo (offset "latest" efectivo)
                    consumidor.seekToEnd(consumidor.assignment());
                    consumidor.assignment().forEach(consumidor::position);
                    listo = true;
                }
            }
        } catch (WakeupException e) {
            // detener() pidió salir
        } finally {
            consumidor.close(Duration.ofSeconds(2));
        }
    }

    public void publicarAudio(Sala sala, byte[] mp3) {
        if (!activo) return;
        productor.send(new ProducerRecord<>(temaAudio(sala), sala.id, mp3));
        registrosEnviados++;
    }

    public void publicarChat(Sala sala, String mensaje) {
        if (!activo) return;
        productor.send(new ProducerRecord<>(TEMA_CHAT, sala.id, mensaje.getBytes(StandardCharsets.UTF_8)));
        registrosEnviados++;
    }

    public long registrosEnviados() {
        return registrosEnviados;
    }

    public long registrosRecibidos() {
        return registrosRecibidos;
    }

    public void detener() {
        if (!activo) return;
        activo = false;
        if (consumidor != null) consumidor.wakeup();
        if (productor != null) productor.close(Duration.ofSeconds(2));
        try {
            if (hiloConsumo != null) hiloConsumo.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
