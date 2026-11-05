#!/bin/sh
# Runs before Logstash starts: these settings only apply to indices created after this.
# Safe to run on every start: everything here replaces what is already there.
set -eu

ES=http://elasticsearch:9200

echo "Keeping 14 days of logs, events and heartbeats"
curl -fsS -X PUT "$ES/_ilm/policy/bank-retention" -H 'Content-Type: application/json' -d '{
  "policy": {"phases": {
    "hot": {"actions": {}},
    "delete": {"min_age": "14d", "actions": {"delete": {}}}
  }}
}'
echo
curl -fsS -X PUT "$ES/_index_template/bank" -H 'Content-Type: application/json' -d '{
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
echo
echo "Done"
