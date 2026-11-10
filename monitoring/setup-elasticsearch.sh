#!/bin/sh
# Runs as the "elastic" superuser before Kibana and Logstash start. Safe to run on every start:
# everything here replaces what is already there, so passwords changed in .env take effect too.
#
#   kibana_system   Kibana's own login (built in; only its password is set here)
#   logstash_bank   may only create and write the bank-* indices
#   grafana_bank    may only read the bank-logs-* indices (and see basic cluster details)
#
# People sign in to Kibana as "elastic". The passwords must not contain " or \.
set -eu

ES=https://elasticsearch:9200
es() { curl -fsS --cacert /certs/ca/ca.crt -u "elastic:$ELASTIC_PASSWORD" -H 'Content-Type: application/json' "$@"; echo; }

echo "Setting Kibana's password"
es -X POST "$ES/_security/user/kibana_system/_password" -d "{\"password\": \"$KIBANA_SYSTEM_PASSWORD\"}"

echo "Creating the Logstash login, which may only write the bank indices"
es -X PUT "$ES/_security/role/bank_logstash_writer" -d '{
  "cluster": ["monitor", "manage_index_templates", "manage_ilm"],
  "indices": [{
    "names": ["bank-*"],
    "privileges": ["create_index", "create_doc", "index", "write", "auto_configure", "view_index_metadata"]
  }]
}'
es -X PUT "$ES/_security/user/logstash_bank" -d "{\"password\": \"$LOGSTASH_ES_PASSWORD\", \"roles\": [\"bank_logstash_writer\"]}"

echo "Creating the Grafana login, which may only read the logs"
es -X PUT "$ES/_security/role/bank_logs_reader" -d '{
  "cluster": ["monitor"],
  "indices": [{"names": ["bank-logs-*"], "privileges": ["read", "view_index_metadata"]}]
}'
es -X PUT "$ES/_security/user/grafana_bank" -d "{\"password\": \"$GRAFANA_ES_PASSWORD\", \"roles\": [\"bank_logs_reader\"]}"

# These settings only apply to indices created after this, which is why Logstash waits for it.
echo "Keeping 14 days of logs, events and heartbeats"
es -X PUT "$ES/_ilm/policy/bank-retention" -d '{
  "policy": {"phases": {
    "hot": {"actions": {}},
    "delete": {"min_age": "14d", "actions": {"delete": {}}}
  }}
}'
es -X PUT "$ES/_index_template/bank" -d '{
  "index_patterns": ["bank-logs-*", "bank-events-*", "bank-health-*"],
  "template": {
    "settings": {"number_of_replicas": 0, "index.lifecycle.name": "bank-retention"},
    "mappings": {"properties": {
      "aggregateId": {"type": "keyword"},
      "data": {"properties": {
        "amount": {"type": "scaled_float", "scaling_factor": 100},
        "balanceAfter": {"type": "scaled_float", "scaling_factor": 100}
      }}
    }}
  }
}'
echo "Done"
