#!/bin/sh
# Writes Alertmanager's settings from the ALERT_* variables, then starts it. Alertmanager's own
# settings file cannot read variables, and this keeps addresses and passwords out of Git.
set -eu

config=/tmp/alertmanager.yml

if [ -z "${ALERT_EMAIL_TO:-}" ]; then
  echo "ALERT_EMAIL_TO is not set, so alerts will NOT be emailed. See .env.example to turn this on."
  cat > "$config" <<EOF
route:
  receiver: nowhere
receivers:
  - name: nowhere
EOF
else
  username="${ALERT_SMTP_USERNAME:-}"
  from="${ALERT_EMAIL_FROM:-$username}"
  if [ -z "$from" ]; then
    echo "Set ALERT_EMAIL_FROM or ALERT_SMTP_USERNAME: alert emails need a sender." >&2
    exit 1
  fi

  auth=""
  if [ -n "$username" ]; then
    # In a file rather than the settings, so that no character in a password needs escaping.
    umask 077
    printf '%s' "${ALERT_SMTP_PASSWORD:-}" > /tmp/smtp-password
    auth="  smtp_auth_username: \"$username\"
  smtp_auth_password_file: /tmp/smtp-password"
  fi

  cat > "$config" <<EOF
global:
  smtp_smarthost: "${ALERT_SMTP_HOST:-smtp.gmail.com:587}"
  smtp_from: "$from"
  smtp_require_tls: ${ALERT_SMTP_REQUIRE_TLS:-true}
$auth

route:
  receiver: email
  # One email per problem and service, not one per measurement.
  group_by: [alertname, job]
  group_wait: 30s
  group_interval: 5m
  # Remind every 4 hours until it is fixed.
  repeat_interval: 4h

receivers:
  - name: email
    email_configs:
      - to: "$ALERT_EMAIL_TO"
        # Also say when the problem has gone away.
        send_resolved: true
EOF
fi

exec /bin/alertmanager \
  --config.file="$config" \
  --storage.path=/alertmanager \
  --web.external-url="http://localhost:${ALERTMANAGER_PORT:-9093}"
