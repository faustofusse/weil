# TODO

- [ ] Drop the dead `accounts.commodity` column. Wait until every installed build (own phones, Play internal, TestFlight) runs a build without per-account currency, because older builds select the column in every account query and break once the drop syncs to them. Then add a guarded `alter table accounts drop column commodity` to `migrateSchema()`, remove its `addColumn`, and bump `SCHEMA_VERSION`. Steps and probe results are in `docs/sync-engine-ddl.md`.
