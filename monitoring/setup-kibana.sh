#!/bin/sh
# Creates the ready-made views in Kibana. Safe to run on every start.
set -eu

KIBANA=http://kibana:5601
# People and this script sign in as "elastic".

data_view() {
  echo "Creating Kibana data view: $2"
  curl -fsS -u "elastic:$ELASTIC_PASSWORD" -X POST "$KIBANA/api/data_views/data_view" \
    -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
    -d "{\"override\": true, \"data_view\": {\"id\": \"$1\", \"name\": \"$2\", \"title\": \"$3\", \"timeFieldName\": \"@timestamp\"}}" \
    > /dev/null
}

data_view bank-logs "Bank logs" "bank-logs-*"
data_view bank-events "Bank events" "bank-events-*"
data_view bank-health "Bank health" "bank-health-*"
echo "Done"
