#!/bin/sh
set -e
export PGPASSWORD=$POSTGRES_PASSWORD;
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE USER $APP_DB_USER WITH PASSWORD '$APP_DB_PASS';
  CREATE DATABASE $APP_DB_NAME;
  GRANT ALL PRIVILEGES ON DATABASE $APP_DB_NAME TO $APP_DB_USER;
  \connect $APP_DB_NAME $APP_DB_USER
  BEGIN;
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
    CREATE TABLE IF NOT EXISTS items (
      record_id VARCHAR(36) NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
      item_id VARCHAR(36) NOT NULL,
      last_modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      supplier VARCHAR(100),
      status VARCHAR(10)
    );
    CREATE UNIQUE INDEX items_index1 on items (item_id);
    INSERT INTO items (item_id, supplier, status) values ('item_id', 'supplier', 'alive');

    CREATE TABLE IF NOT EXISTS items_language (
      record_id VARCHAR(36) NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
      item_id VARCHAR(36) NOT NULL,
      language_id VARCHAR(5) NOT NULL,
      description VARCHAR(100),
      features VARCHAR(1000)
    );
    CREATE UNIQUE INDEX items_language_index1 on items_language (item_id, language_id);
    INSERT INTO items_language (item_id, language_id, description, features) values ('item_id', 'en', 'description', 'features');
    INSERT INTO items_language (item_id, language_id, description, features) values ('item_id', 'fr', 'description', 'features');
  COMMIT;
EOSQL
