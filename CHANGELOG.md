# Changelog

## 1.0.0 (12 November 2026)

Built from Module 40's code seed (`_tools/module-40-code/`, 17 files, and the three lab solutions in
`_tools/module-40-labs/`).

- Gradle build to the course baseline: wrapper 9.9.1 checked by sha256, version catalog,
  lockfiles, `-Xlint:all -Werror`, Spotless with google-java-format 2.0.0, JaCoCo line floor 0.80
  (the `stack` package excluded and covered by the stack tests), one `JavaExec` task per program.
- The seed's sealed outcomes replaced by result4j `Result` where they are expected failures:
  `SegmentArchive.read` (`Damaged`), `BaseBackup.restoreFiles` (`Verified` or `Rejected`),
  `PointInTimeRestore.restore` (`Recovered` or the sealed `Failure`: `StoppedShort`,
  `BackupRejected`), `RestoreChecker.declare` (`Declared` or `Refused`). `Declared` keeps its private
  constructor; a stopped-short replay is still always refused, through an exhaustive switch.
- The programs print to a stream they are given, so their output is tested against golden copies.
- The object store: the archived MinIO the seed built from source is replaced by RustFS 1.0.5 (the
  owner's decision of 12 November 2026, Module 29's choice), with TLS from a lab authority because
  pgBackRest reaches S3 over TLS only, and Object Lock with COMPLIANCE retention on the bucket.
  `docs/backup-tool-and-store.md` records the test of pgBackRest against it.
- pgBackRest 2.60.0 installed as the PostgreSQL project's pinned Debian package
  (`2.60.0-1.pgdg13+1`) instead of being built from its source tarball.
- Seed defect fixed: `scripts/restore-drill.sh` used GNU `date -d`, which macOS does not have; the
  timing now uses Python's clock, in milliseconds. The script has a dry run by default, `--run` and
  `--yes`, and checks the copy with the JDBC checker instead of a row count and hash in SQL.
- Seed defect fixed: `compose.yaml` took credentials from an environment file, so the store's keys
  and the cipher passphrase would have been in the containers' environment; they are now an
  include file of pgBackRest's and Compose secrets, generated into `.secrets/`.
- Seed defect fixed: the seed's captured output of `RecoveryObjectivesCli` was older than its code
  (it printed "a cross-region replica per cluster" where the code says "a logical-replication copy
  per cluster in region-b"); the output is captured again from the code.
- New: the lab (`compose.yaml`, `postgres.Dockerfile`, `db/`, `scripts/stack.sh`,
  `config/stack.properties`), the load generator `ConfirmLoadCli`, the JDBC checker
  (`ConfirmManifest`, `ConfirmChecker`, `RestoreCheckCli`), the pilot light (`PilotLightCli`,
  `PilotLightPromotion`), `ArchiveLagCli`, `BackupHealth` and `PgBackRestInfo`, the stack tests,
  `scripts/mutants.sh` and `scripts/measure.sh`.
- Found and handled: `pgbackrest verify` exits 0 when it finds a damaged file; the project reads the
  per-backup status it prints.
