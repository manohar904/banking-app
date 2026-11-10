#!/bin/sh
# Makes the stack's own certificate authority (CA), and a certificate signed by it for each name given.
# Runs on every start and only makes what is missing, or what expires within 30 days. The volume
# looks like this, and each part of the bank mounts only its own folder and ca/:
#
#   ca/ca.crt            the CA's certificate, which everyone trusts
#   ca-private/ca.key    the CA's key. Only this step sees it.
#   <name>/tls.crt       a certificate for <name> (and localhost), valid for about two years
#   <name>/tls.key       its private key
set -eu
cd /certs
umask 022

if [ ! -f ca-private/ca.key ]; then
  echo "Making the certificate authority"
  mkdir -p ca ca-private
  chmod 700 ca-private
  openssl req -x509 -new -nodes -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
    -keyout ca-private/ca.key -out ca/ca.crt -days 3650 -subj "/CN=Bank local certificate authority" 2> /dev/null
fi

for name in "$@"; do
  if [ -f "$name/tls.crt" ] \
      && openssl x509 -checkend 2592000 -noout -in "$name/tls.crt" > /dev/null \
      && openssl verify -CAfile ca/ca.crt "$name/tls.crt" > /dev/null 2>&1; then
    continue
  fi
  echo "Making a certificate for $name"
  mkdir -p "$name"
  openssl req -new -nodes -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
    -keyout "$name/tls.key" -out "$name/tls.csr" -subj "/CN=$name" 2> /dev/null
  printf 'subjectAltName=DNS:%s,DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth,clientAuth\n' \
    "$name" > "$name/tls.ext"
  openssl x509 -req -in "$name/tls.csr" -CA ca/ca.crt -CAkey ca-private/ca.key -CAcreateserial \
    -CAserial ca-private/ca.srl -out "$name/tls.crt" -days 825 -extfile "$name/tls.ext" 2> /dev/null
  rm "$name/tls.csr" "$name/tls.ext"
  # Readable by the non-root user each service runs as. Only that service mounts this folder.
  chmod 644 "$name/tls.key"
done
echo "Certificates ready"
