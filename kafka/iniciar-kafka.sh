#!/usr/bin/env bash
# Inicia Kafka para GameShakers Radio (Linux / macOS).
# Uso: KAFKA_HOME=/ruta/a/kafka_2.13-4.4.0 ./iniciar-kafka.sh
set -e
cd "$(dirname "$0")"
KAFKA_HOME="${KAFKA_HOME:-$HOME/kafka}"
if [ ! -x "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
  echo "No encuentro Kafka en $KAFKA_HOME. Indica la carpeta con KAFKA_HOME=..." >&2
  exit 1
fi
# La primera vez hay que formatear la carpeta de datos con un identificador de clúster
if [ ! -f datos/meta.properties ]; then
  ID="$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)"
  "$KAFKA_HOME/bin/kafka-storage.sh" format --standalone -t "$ID" -c radio-kafka.properties
fi
exec "$KAFKA_HOME/bin/kafka-server-start.sh" radio-kafka.properties
