#!/bin/sh
# Gives each service its own database and its own login, which can use that database and no other.
# Runs as the main PostgreSQL user on every start, because PostgreSQL's own init scripts only run on
# an empty volume. Safe to repeat: it creates only what is missing, sets each login's password from
# the environment, and hands over anything in a service's database that another user owns (tables
# from before each service had its own login, or from restoring an older backup).
set -eu
export PGOPTIONS="-c client_min_messages=warning"

# database, login, and the environment variable holding the login's password
setup() {
  db=$1 role=$2 password_var=$3
  eval "[ -n \"\${$password_var:-}\" ]" || { echo "$password_var is not set" >&2; exit 1; }

  psql -X -q -v ON_ERROR_STOP=1 -d postgres -v db="$db" -v role="$role" -v password_var="$password_var" <<'SQL'
\getenv password :password_var
select format('create role %I login', :'role')
  where not exists (select 1 from pg_roles where rolname = :'role') \gexec
alter role :"role" with login nosuperuser nocreatedb nocreaterole noinherit password :'password';
select format('create database %I owner %I', :'db', :'role')
  where not exists (select 1 from pg_database where datname = :'db') \gexec
alter database :"db" owner to :"role";
-- Only its own service may connect. The main user still can, being a superuser.
revoke all on database :"db" from public;
grant connect, temporary on database :"db" to :"role";
SQL

  psql -X -q -v ON_ERROR_STOP=1 -d "$db" -v role="$role" <<'SQL'
select format('alter table public.%I owner to %I', tablename, :'role')
  from pg_tables where schemaname = 'public' and tableowner <> :'role' \gexec
select format('alter sequence public.%I owner to %I', sequencename, :'role')
  from pg_sequences where schemaname = 'public' and sequenceowner <> :'role' \gexec
select format('alter view public.%I owner to %I', viewname, :'role')
  from pg_views where schemaname = 'public' and viewowner <> :'role' \gexec
select format('alter routine %s owner to %I', p.oid::regprocedure, :'role')
  from pg_proc p where p.pronamespace = 'public'::regnamespace and pg_get_userbyid(p.proowner) <> :'role' \gexec
SQL
  echo "$db: ready for $role"
}

setup bank_customers customer_service CUSTOMER_DB_PASSWORD
setup bank_accounts account_service ACCOUNT_DB_PASSWORD
setup bank_auth auth_service AUTH_DB_PASSWORD
