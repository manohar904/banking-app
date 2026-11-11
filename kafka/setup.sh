#!/bin/sh
# Makes the bank's topics, and says what each Kafka login may do. Kafka refuses anything not allowed
# here (see compose.yaml). Runs as "admin" on every start, once all three brokers are up. Safe to
# repeat: a topic or rule that is already there is left as it is.
#
#   bank       the four apps: write events and heartbeats, and read events (transaction emails)
#   filebeat   write log lines
#   logstash   read events, heartbeats and log lines, to copy them to Elasticsearch
#   admin      Kafka itself, and this script: anything
set -eu

config=/tmp/admin.properties
printf 'security.protocol=SASL_SSL\nssl.truststore.type=PEM\nssl.truststore.location=/certs/ca/ca.crt\nsasl.mechanism=PLAIN\nsasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="%s";\n' \
  "$ADMIN_PASSWORD" > "$config"
servers=kafka-1:9092,kafka-2:9092,kafka-3:9092

# Three copies on the three brokers; a write counts once two have it (the brokers' defaults).
# One partition each, so every event on a topic stays in the order it was sent.
for topic in bank.events bank.health bank.logs; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$servers" --command-config "$config" \
    --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 3 > /dev/null
done
echo "Topics ready"

allow() {
  /opt/kafka/bin/kafka-acls.sh --bootstrap-server "$servers" --command-config "$config" --add "$@" > /dev/null
}

echo "The apps may write events and heartbeats, and read events"
allow --allow-principal User:bank --operation Write --operation Describe --topic bank.events --topic bank.health
allow --allow-principal User:bank --operation Read --topic bank.events
allow --allow-principal User:bank --operation Read --group transaction-emails

echo "Filebeat may write log lines"
allow --allow-principal User:filebeat --operation Write --operation Describe --topic bank.logs

echo "Logstash may read everything it copies to Elasticsearch"
allow --allow-principal User:logstash --operation Read --operation Describe \
  --topic bank.events --topic bank.health --topic bank.logs
allow --allow-principal User:logstash --operation Read --group logstash

/opt/kafka/bin/kafka-acls.sh --bootstrap-server "$servers" --command-config "$config" --list | grep -c "principal=User" \
  | xargs -I{} echo "Done: {} rules"
rm -f "$config"
