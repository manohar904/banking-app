#!/bin/sh
# Puts the bank's databases back as they were in a backup. Run from the repository root:
#
#   docker compose exec backup sh /backup.sh list          pick a backup
#   sh scripts/restore-backup.sh 2026-11-06_020000          restore every database in it
#   sh scripts/restore-backup.sh 2026-11-06_020000 bank_accounts    or only some
#
# The services are stopped meanwhile, and started again afterwards even if the restore fails. If it
# fails, nothing was changed. The databases it replaces are kept as <name>_before_restore.
set -eu

# Stops Git Bash on Windows turning /backup.sh into a Windows path.
export MSYS_NO_PATHCONV=1

[ $# -ge 1 ] || { sed -n '2,9p' "$0"; exit 2; }

services="gateway customer-service account-service auth-service"
docker compose stop $services
trap 'docker compose start $services' EXIT
docker compose exec -T backup sh /backup.sh restore "$@"
