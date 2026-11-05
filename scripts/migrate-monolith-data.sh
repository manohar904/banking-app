#!/bin/sh
# Copies data from the old single database into the three service databases, keeping every id.
# Run once, from the repository root, when moving an existing stack to the microservices:
#
#   docker compose down --remove-orphans    (removes the old app container; the data volume stays)
#   sh scripts/migrate-monolith-data.sh
#   docker compose up -d --build
#
# The old database is only read. Drop it once you have checked the result.
# If the new tables already hold data, this stops without copying anything.
set -eu

# No first staff login during this start-up: the old database brings its own logins.
export BANK_ADMIN_EMAIL=
export BANK_ADMIN_PASSWORD=

# Starting the services creates their databases and tables. They stop again so nothing changes during the copy.
docker compose up -d --build --wait customer-service account-service auth-service
docker compose stop customer-service account-service auth-service

docker compose exec -T postgres sh -eu <<'SCRIPT'
source_db="${POSTGRES_DB}"
user="${POSTGRES_USER}"

row_count() { psql -U "$user" -d "$1" -tAc "select count(*) from $2"; }

for target in bank_customers:customers bank_accounts:accounts bank_accounts:account_transactions bank_auth:users; do
  if [ "$(row_count "${target%%:*}" "${target##*:}")" != "0" ]; then
    echo "Stopping: ${target##*:} in ${target%%:*} already has data, so it looks migrated already." >&2
    exit 1
  fi
done

copy() {
  target_db="$1"
  shift
  tables=""
  for table in "$@"; do tables="$tables --table=public.$table"; done
  # Data only: the tables came from the service's own migrations. Also restores the id sequences.
  pg_dump -U "$user" -d "$source_db" --data-only $tables \
    | psql -U "$user" -d "$target_db" -v ON_ERROR_STOP=1 --single-transaction --quiet > /dev/null
}

copy bank_customers customers
copy bank_accounts accounts account_transactions
copy bank_auth users

for pair in "$source_db:customers bank_customers:customers" \
            "$source_db:accounts bank_accounts:accounts" \
            "$source_db:account_transactions bank_accounts:account_transactions" \
            "$source_db:users bank_auth:users"; do
  old="${pair%% *}"
  new="${pair##* }"
  old_count="$(row_count "${old%%:*}" "${old##*:}")"
  new_count="$(row_count "${new%%:*}" "${new##*:}")"
  echo "${old##*:}: $old_count rows in the old database, $new_count in ${new%%:*}"
  [ "$old_count" = "$new_count" ]
done
SCRIPT

echo "Done. Start everything with: docker compose up -d --build"
