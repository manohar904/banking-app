#!/bin/sh
# Gives each service its own database with two logins of its own, neither of which can use any other
# database:
#
#   <name>_owner     owns the database and its tables. Only the migration step uses it.
#   <name>_service   what the running service uses. It may read and write rows, and nothing more;
#                    the migration step grants that (see after-migrate/afterMigrate.sql).
#
# Runs as the main PostgreSQL user on every start, because PostgreSQL's own init scripts only run on
# an empty volume. Safe to repeat: it creates only what is missing, sets each login's password from
# the environment, and hands the owner anything in the database that someone else owns (tables from
# before the owner logins existed, or from restoring an older backup).
set -eu
export PGOPTIONS="-c client_min_messages=warning"

# login, and the environment variable holding its password
login() {
  role=$1 password_var=$2
  eval "[ -n \"\${$password_var:-}\" ]" || { echo "$password_var is not set" >&2; exit 1; }
  psql -X -q -v ON_ERROR_STOP=1 -d postgres -v role="$role" -v password_var="$password_var" <<'SQL'
\getenv password :password_var
select format('create role %I login', :'role')
  where not exists (select 1 from pg_roles where rolname = :'role') \gexec
alter role :"role" with login nosuperuser nocreatedb nocreaterole noinherit password :'password';
SQL
}

# database, its owner, and the login its service uses
setup() {
  db=$1 owner=$2 app=$3
  psql -X -q -v ON_ERROR_STOP=1 -d postgres -v db="$db" -v owner="$owner" -v app="$app" <<'SQL'
select format('create database %I owner %I', :'db', :'owner')
  where not exists (select 1 from pg_database where datname = :'db') \gexec
alter database :"db" owner to :"owner";
-- Only this service's two logins may connect. The main user still can, being a superuser.
revoke all on database :"db" from public;
grant connect, temporary on database :"db" to :"owner", :"app";
SQL

  psql -X -q -v ON_ERROR_STOP=1 -d "$db" -v owner="$owner" <<'SQL'
-- Nobody but the owner may create anything here.
revoke create on schema public from public;
select format('alter table public.%I owner to %I', tablename, :'owner')
  from pg_tables where schemaname = 'public' and tableowner <> :'owner' \gexec
select format('alter sequence public.%I owner to %I', sequencename, :'owner')
  from pg_sequences where schemaname = 'public' and sequenceowner <> :'owner' \gexec
select format('alter view public.%I owner to %I', viewname, :'owner')
  from pg_views where schemaname = 'public' and viewowner <> :'owner' \gexec
select format('alter routine %s owner to %I', p.oid::regprocedure, :'owner')
  from pg_proc p where p.pronamespace = 'public'::regnamespace and pg_get_userbyid(p.proowner) <> :'owner' \gexec
SQL
  echo "$db: owned by $owner, used by $app"
}

login customer_owner CUSTOMER_DB_OWNER_PASSWORD
login customer_service CUSTOMER_DB_PASSWORD
login account_owner ACCOUNT_DB_OWNER_PASSWORD
login account_service ACCOUNT_DB_PASSWORD
login auth_owner AUTH_DB_OWNER_PASSWORD
login auth_service AUTH_DB_PASSWORD

setup bank_customers customer_owner customer_service
setup bank_accounts account_owner account_service
setup bank_auth auth_owner auth_service
