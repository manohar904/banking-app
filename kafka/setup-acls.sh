#!/bin/sh
# Says what each Kafka login may do. Kafka refuses anything not allowed here (see compose.yaml).
# Runs as "admin" on every start, after Kafka is up. Safe to repeat: adding a rule that is already
# there changes nothing.
#
#   bank       the four apps: write events and heartbeats, and read events (transaction emails)
#   filebeat   write log lines
#   logstash   read events, heartbeats and log lines, to copy them to Elasticsearch
#   admin      Kafka itself, and this script: anything
set -eu

config=/tmp/admin.properties
printf 'security.protocol=SASL_SSL\nssl.truststore.type=PEM\nssl.truststore.location=/certs/ca/ca.crt\nsasl.mechanism=PLAIN\nsasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="%s";\n' \
  "$ADMIN_PASSWORD" > "$config"

allow() {
  /opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config "$config" --add "$@" > /dev/null
}

echo "The apps may write events and heartbeats, and read events"
# Create: Kafka makes a topic the first time it is written to.
allow --allow-principal User:bank --operation Write --operation Describe --operation Create \
  --topic bank.events --topic bank.health
allow --allow-principal User:bank --operation Read --topic bank.events
allow --allow-principal User:bank --operation Read --group transaction-emails

echo "Filebeat may write log lines"
allow --allow-principal User:filebeat --operation Write --operation Describe --operation Create --topic bank.logs

echo "Logstash may read everything it copies to Elasticsearch"
allow --allow-principal User:logstash --operation Read --operation Describe \
  --topic bank.events --topic bank.health --topic bank.logs
allow --allow-principal User:logstash --operation Read --group logstash

/opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config "$config" --list | grep -c "principal=User" \
  | xargs -I{} echo "Done: {} rules"
rm -f "$config"
