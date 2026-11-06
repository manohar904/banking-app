#!/bin/sh
# Daily PostgreSQL backups for the bank. Every backup is restored into a scratch database to prove
# it works. Runs in the "backup" container; see compose.yaml and README.md ("Backups").
#
#   docker compose exec backup sh /backup.sh now             take a backup now
#   docker compose exec backup sh /backup.sh list            show the backups
#   docker compose exec backup sh /backup.sh verify [NAME]   test-restore one (the newest by default)
#   sh scripts/restore-backup.sh NAME                        put one back (stops the bank meanwhile)
set -eu
export PGOPTIONS="-c client_min_messages=warning"

dir=/backups
keep="${BACKUP_KEEP:-7}"
at="${BACKUP_TIME:-02:00}"
metrics=/tmp/metrics.prom
check_db=bank_restore_check
retry_seconds=1800

log() { echo "$(date '+%F %T %Z') $*"; }
die() { log "ERROR: $*" >&2; exit 1; }
q() { psql -X -q -v ON_ERROR_STOP=1 -d "${2:-postgres}" -tAc "$1"; }

# Every database except PostgreSQL's own and the ones this script creates while restoring.
databases() {
  q "select datname from pg_database
     where not datistemplate and datname <> 'postgres' and datname <> '$check_db'
       and datname not like '%\_\_restoring' and datname not like '%\_before\_restore'
     order by 1"
}

# Finished backups, oldest first. Unfinished ones are named .incomplete-* and never listed.
backups() {
  ls -1 "$dir" 2>/dev/null | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}$' || true
}

verified_backups() {
  for b in $(backups); do [ -f "$dir/$b/VERIFIED" ] && echo "$b"; done
  return 0
}

# Restores each dump into a scratch database and checks every table came back.
verify() {
  name="${1:-$(backups | tail -n 1)}"
  [ -n "$name" ] && [ -d "$dir/$name" ] || die "No backup called '${name}'. See: sh /backup.sh list"
  rm -f "$dir/$name/VERIFIED" "$dir/$name/VERIFY_FAILED"
  report="$dir/$name/.verify-report"
  : > "$report"
  trap 'q "drop database if exists $check_db" >/dev/null 2>&1 || true; mv "$report" "$dir/$name/VERIFY_FAILED" 2>/dev/null || true' EXIT

  (cd "$dir/$name" && sha256sum -c -s SHA256SUMS) || die "$name: files do not match their checksums"

  for f in "$dir/$name"/*.dump; do
    db=$(basename "$f" .dump)
    q "drop database if exists $check_db"
    q "create database $check_db"
    pg_restore --exit-on-error --no-owner -d "$check_db" "$f" || die "$name: $db could not be restored"
    expected=$(pg_restore --list "$f" | awk '$4 == "TABLE" && $5 != "DATA"' | wc -l)
    found=$(q "select count(*) from pg_tables where schemaname not in ('pg_catalog', 'information_schema')" "$check_db")
    [ "$expected" -eq "$found" ] || die "$name: $db has $expected tables in the backup but $found after restoring"
    rows=$(q "select coalesce(sum((xpath('/row/n/text()',
                query_to_xml(format('select count(*) as n from %I.%I', schemaname, tablename), false, true, '')))[1]::text::bigint), 0)
              from pg_tables where schemaname not in ('pg_catalog', 'information_schema')" "$check_db")
    log "$name: $db restored, $found tables, $rows rows" | tee -a "$report"
  done

  q "drop database $check_db"
  trap - EXIT
  mv "$report" "$dir/$name/VERIFIED"
  log "$name: verified"
}

# Keeps the newest $keep verified backups, and anything newer than the oldest of them.
prune() {
  count=$(verified_backups | wc -l)
  [ "$count" -gt "$keep" ] || return 0
  oldest_kept=$(verified_backups | tail -n "$keep" | head -n 1)
  for b in $(backups); do
    [ "$b" \< "$oldest_kept" ] || continue
    rm -rf "${dir:?}/$b"
    log "Removed old backup $b"
  done
}

backup_now() {
  name=$(date +%Y-%m-%d_%H%M%S)
  tmp="$dir/.incomplete-$name"
  rm -rf "$dir"/.incomplete-*
  mkdir -p "$tmp"
  trap 'rm -rf "$tmp"' EXIT

  dbs=$(databases)
  [ -n "$dbs" ] || die "found no databases to back up"
  for db in $dbs; do
    pg_dump -Fc -d "$db" -f "$tmp/$db.dump" || die "pg_dump of $db failed"
  done
  (cd "$tmp" && sha256sum ./*.dump > SHA256SUMS)
  date +%s > "$tmp/completed_at"
  sync
  mv "$tmp" "$dir/$name"
  trap - EXIT
  log "Backup $name written: $(echo $dbs), $(du -sh "$dir/$name" | cut -f1)"

  verify "$name"
  prune
}

# Puts a backup back. Every database is first restored under a temporary name; only when all of them
# worked are they swapped in. The databases they replace are kept as <name>_before_restore.
restore() {
  name="${1:-}"
  [ -n "$name" ] && [ -d "$dir/$name" ] || die "Say which backup to restore. See: sh /backup.sh list"
  shift
  (cd "$dir/$name" && sha256sum -c -s SHA256SUMS) || die "$name: files do not match their checksums"
  [ -f "$dir/$name/VERIFIED" ] || log "Warning: $name never passed its test restore."

  if [ $# -gt 0 ]; then dbs="$*"; else dbs=$(cd "$dir/$name" && ls ./*.dump | sed 's|^\./||; s|\.dump$||'); fi
  for db in $dbs; do
    [ -f "$dir/$name/$db.dump" ] || die "$name has no backup of $db"
  done

  in_use=$(q "select string_agg(distinct datname, ', ') from pg_stat_activity
              where datname in ($(for db in $dbs; do printf "'%s'," "$db"; done)'') and pid <> pg_backend_pid()")
  [ -z "$in_use" ] || die "$in_use still in use. Stop the services first, or use scripts/restore-backup.sh."

  trap 'for db in $dbs; do q "drop database if exists ${db}__restoring" >/dev/null 2>&1 || true; done' EXIT
  for db in $dbs; do
    q "drop database if exists ${db}__restoring"
    q "create database ${db}__restoring"
    pg_restore --exit-on-error -d "${db}__restoring" "$dir/$name/$db.dump" || die "$db could not be restored; nothing was changed"
    log "Restored $db from $name"
  done

  for db in $dbs; do
    q "drop database if exists ${db}_before_restore"
    if [ -n "$(q "select 1 from pg_database where datname = '$db'")" ]; then
      q "alter database $db rename to ${db}_before_restore"
    fi
    q "alter database ${db}__restoring rename to $db"
  done
  trap - EXIT
  log "Done. The replaced databases are kept as <name>_before_restore until the next restore."
}

list() {
  [ -n "$(backups)" ] || { echo "No backups yet."; return 0; }
  for b in $(backups); do
    if [ -f "$dir/$b/VERIFIED" ]; then status=verified
    elif [ -f "$dir/$b/VERIFY_FAILED" ]; then status="TEST RESTORE FAILED"
    else status="not tested"; fi
    printf '%s  %6s  %s\n' "$b" "$(du -sh "$dir/$b" | cut -f1)" "$status"
  done
}

last_success_epoch() {
  latest=$(verified_backups | tail -n 1)
  if [ -n "$latest" ]; then cat "$dir/$latest/completed_at"; else echo 0; fi
}

write_metrics() {
  latest=$(verified_backups | tail -n 1)
  size=0
  [ -z "$latest" ] || size=$(du -sb "$dir/$latest" | cut -f1)
  cat > "$metrics.tmp" <<EOF
# HELP bank_backup_last_success_timestamp_seconds When the newest verified backup finished (0 if none).
# TYPE bank_backup_last_success_timestamp_seconds gauge
bank_backup_last_success_timestamp_seconds $(last_success_epoch)
# HELP bank_backup_last_run_success Whether the latest backup attempt, including its test restore, worked.
# TYPE bank_backup_last_run_success gauge
bank_backup_last_run_success $(cat /tmp/last_run_success 2>/dev/null || echo 1)
# HELP bank_backup_size_bytes Size of the newest verified backup.
# TYPE bank_backup_size_bytes gauge
bank_backup_size_bytes $size
# HELP bank_backup_count Verified backups kept.
# TYPE bank_backup_count gauge
bank_backup_count $(verified_backups | wc -l)
EOF
  mv "$metrics.tmp" "$metrics"
}

# Answers one HTTP request from Prometheus with the metrics file. Started by nc for each connection.
http() {
  while IFS= read -r line; do
    line=$(printf '%s' "$line" | tr -d '\r')
    [ -n "$line" ] || break
  done
  printf 'HTTP/1.0 200 OK\r\nContent-Type: text/plain; version=0.0.4\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' \
    "$(wc -c < "$metrics")"
  cat "$metrics"
}

# Backs up once a day, at BACKUP_TIME or as soon after it as the computer is on. If the newest good
# backup is more than a day old, for example after a week switched off, it backs up straight away.
schedule() {
  mkdir -p "$dir"
  write_metrics
  nc -lk -p 9188 -e sh /backup.sh http &
  trap 'kill $! 2>/dev/null; exit 0' TERM INT
  log "Backing up daily at $at ($(date +%Z)) into $dir, keeping $keep verified backups."
  at_hm=$(echo "$at" | tr -d :)
  last_try=0

  while true; do
    now=$(date +%s)
    newest=$(verified_backups | tail -n 1)
    done_today=false
    [ "${newest%%_*}" = "$(date +%F)" ] && done_today=true
    due=false
    if [ "1$(date +%H%M)" -ge "1$at_hm" ] && [ "$done_today" = false ]; then due=true; fi
    if [ $((now - $(last_success_epoch))) -gt 86400 ]; then due=true; fi

    if [ "$due" = true ] && [ $((now - last_try)) -ge "$retry_seconds" ]; then
      last_try=$now
      sh /backup.sh now || log "Backup failed. Trying again in $((retry_seconds / 60)) minutes."
    fi
    # Also picks up backups taken or removed by hand.
    write_metrics
    sleep 60 & wait $!
  done
}

case "${1:-}" in
  schedule) schedule ;;
  # Runs the backup as its own process, where set -e applies, and records whether it worked.
  now)
    if sh "$0" backup; then echo 1 > /tmp/last_run_success
    else echo 0 > /tmp/last_run_success; exit 1; fi ;;
  backup) backup_now ;;
  verify) shift; verify "${1:-}" ;;
  restore) shift; restore "$@" ;;
  list) list ;;
  http) http ;;
  *) sed -n '2,8p' "$0"; exit 2 ;;
esac
