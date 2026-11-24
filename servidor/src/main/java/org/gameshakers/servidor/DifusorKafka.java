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
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.gameshakers.comun.Protocolo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Los mensajes de chat pasan por Kafka: el servidor los publica en el tema {@link Protocolo#TEMA_KAFKA}
 * (clave = id de la sala) y los reparte a la sala cuando los consume. Como Kafka los guarda,
 * al reiniciar el servidor se recupera el historial de cada sala.
 */
public class DifusorKafka implements Difusor {

    /** Cuántos mensajes recientes se leen de Kafka al arrancar para el historial. */
    private static final int LEER_AL_ARRANCAR = 300;

    private final String servidores;
    private final TopicPartition particion = new TopicPartition(Protocolo.TEMA_KAFKA, 0);
    private final List<MensajeChat> historial = new ArrayList<>();
    private KafkaProducer<String, String> productor;
    private KafkaConsumer<String, String> consumidor;
    private Thread hilo;
    private volatile boolean activo;

    public DifusorKafka(String servidores) {
        this.servidores = servidores;
    }

    @Override
    public void iniciar(Consumer<MensajeChat> alRecibir) throws Exception {
        crearTema();

        Properties pc = new Properties();
        pc.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        pc.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        pc.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        pc.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        pc.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50);
        consumidor = new KafkaConsumer<>(pc);
        // se asigna la partición directamente (sin grupo de consumidores): cada servidor lee todo
        consumidor.assign(List.of(particion));
        long fin = consumidor.endOffsets(List.of(particion)).get(particion);

        // 1) historial: los últimos mensajes guardados en Kafka
        consumidor.seek(particion, Math.max(0, fin - LEER_AL_ARRANCAR));
        long limite = System.currentTimeMillis() + 5000;
        while (consumidor.position(particion) < fin && System.currentTimeMillis() < limite) {
            for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(200))) {
                if (r.offset() >= fin) break;
                MensajeChat m = MensajeChat.desdeKafka(r.key(), r.value());
                if (m != null) historial.add(m);
            }
        }
        consumidor.seek(particion, fin);   // 2) desde aquí, solo lo nuevo

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        pp.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        pp.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        productor = new KafkaProducer<>(pp);

        activo = true;
        hilo = new Thread(() -> consumir(alRecibir), "kafka-consumidor");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void crearTema() throws Exception {
        Properties pa = new Properties();
        pa.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        pa.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        pa.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        try (Admin admin = Admin.create(pa)) {
            try {
                admin.describeCluster().clusterId().get(8, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | ExecutionException e) {
                throw new IllegalStateException("No se pudo conectar a Kafka en " + servidores
                        + ". ¿Está encendido? Para usar el servidor sin Kafka, no indiques chat.kafka.servidores.", e);
            }
            if (!admin.listTopics().names().get(8, TimeUnit.SECONDS).contains(Protocolo.TEMA_KAFKA)) {
                try {
                    admin.createTopics(List.of(new NewTopic(Protocolo.TEMA_KAFKA, 1, (short) 1)
                            .configs(Map.of("retention.ms", String.valueOf(7L * 24 * 3600 * 1000)))))
                            .all().get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) throw e;
                }
            }
        }
    }

    private void consumir(Consumer<MensajeChat> alRecibir) {
        try {
            while (activo) {
                for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(200))) {
                    MensajeChat m = MensajeChat.desdeKafka(r.key(), r.value());
                    if (m != null) alRecibir.accept(m);
                }
            }
        } catch (WakeupException e) {
            // detener() pidió salir
        } finally {
            consumidor.close(Duration.ofSeconds(2));
        }
    }

    @Override
    public void publicar(MensajeChat m) {
        productor.send(new ProducerRecord<>(Protocolo.TEMA_KAFKA, m.sala().id, m.comoValorKafka()));
    }

    @Override
    public List<MensajeChat> historialPrevio() {
        return historial;
    }

    @Override
    public String descripcion() {
        return "Kafka en " + servidores + " (tema " + Protocolo.TEMA_KAFKA + ")";
    }

    @Override
    public void detener() {
        activo = false;
        if (consumidor != null) consumidor.wakeup();
        if (productor != null) productor.close(Duration.ofSeconds(2));
    }
}
