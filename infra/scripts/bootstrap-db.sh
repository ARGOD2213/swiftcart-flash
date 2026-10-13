#!/usr/bin/env bash
# One-off after the first `terraform apply`: creates the application role and database on Aurora. Runs psql in a
# short-lived pod inside the cluster, because Aurora has no public endpoint. The app runs Flyway on startup.
# Needs: terraform state access, aws cli, jq, kubectl pointed at the cluster.
set -euo pipefail

tf() { terraform -chdir="$(dirname "$0")/../terraform" output -json "$@"; }
region=$(tf region | jq -r .)
host=$(tf aurora_endpoint | jq -r .)
secret() { aws secretsmanager get-secret-value --region "$region" --secret-id "$1" --query SecretString --output text; }
master=$(secret "$(tf aurora_master_secret_arn | jq -r .)")
app=$(secret "$(tf app_secret_name | jq -r .)")

sql=$(cat <<SQL
\set ON_ERROR_STOP on
select 'create role swiftcart login' where not exists (select from pg_roles where rolname = 'swiftcart') \gexec
alter role swiftcart password '$(jq -r .DB_PASSWORD <<<"$app")';
grant swiftcart to current_user;
select 'create database swiftcart owner swiftcart' where not exists (select from pg_database where datname = 'swiftcart') \gexec
SQL
)

# the SQL (with the password) goes over stdin, never into the pod spec
kubectl run "sc-db-bootstrap-$(date +%s)" -n swiftcart --rm -i --restart=Never --quiet \
  --image=postgres:16-alpine \
  --env="PGPASSWORD=$(jq -r .password <<<"$master")" \
  -- psql "host=$host port=5432 dbname=postgres user=$(jq -r .username <<<"$master") sslmode=require" <<<"$sql"
echo "Bootstrapped $host"
