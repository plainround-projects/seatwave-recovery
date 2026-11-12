# seatwave-recovery

Module 40 of System Design Fundamentals, "Disaster recovery": SeatWave's DR plan, a timed restore
drill and one chaos experiment with its hypothesis and result. It has two halves that share one
rule, **every restore is verified against a manifest before it is declared good, and no drill runs
without abort conditions**:

- JDK-only programs: a point-in-time restore on real files in four scenarios, RPO and RTO per DR
  tier from the mechanism, a timed drill with a size model, the chaos experiment and game day, and
  the recovery plan as data with its check.
- A lab on real tools: PostgreSQL 18.6 archiving its log with pgBackRest 2.60.0 to an
  S3-compatible store (RustFS 1.0.5, over TLS, the bucket under Object Lock), an empty instance to
  restore into, and a second PostgreSQL holding a logical copy of the bookings tables (the pilot
  light's mechanism). A load generator writes the drill manifest; the drill script restores to a
  chosen moment and a JDBC checker compares the copy with the manifest.

One machine has no regions: the lab proves mechanisms and measures this laptop, never a region.

## Prerequisites

- JDK 25 (the build's toolchain finds it; the runs used Eclipse Temurin 25.0.2). Gradle comes
  through the wrapper (9.9.1, checked by sha256).
- For the lab only: Docker with Compose v2 (run here on Docker 24.0.6 and Compose v2.23.0), `curl`
  with `--aws-sigv4` (curl 7.75 or newer), `openssl` and `python3`. `./gradlew check` needs none of
  them.

## Dependencies (each checked on Maven Central on 12 November 2026)

| Coordinates | License | Why the JDK alone is not enough |
|---|---|---|
| `com.github.sviperll:result4j:1.2.0` | Apache-2.0 | expected failures as values with sealed error types, the course's rule |
| `org.postgresql:postgresql:42.7.14` | BSD-2-Clause | the JDK has no PostgreSQL wire protocol |
| `com.zaxxer:HikariCP:7.1.0` | Apache-2.0 | the load generator's bounded pool, the client side of the primary-kill experiment |
| `tools.jackson.core:jackson-databind:3.3.0` | Apache-2.0 | reading `pgbackrest info --output=json` into records |
| `ch.qos.logback:logback-classic:1.6.6` | EPL-1.0 or LGPL-2.1 | the pool logs through SLF4J; only warnings are shown |
| `org.junit:junit-bom:6.1.3`, `org.assertj:assertj-core:3.27.7` | EPL-2.0, Apache-2.0 | tests |
| Spotless 8.11.0 with google-java-format 2.0.0, JaCoCo 0.8.16 | Apache-2.0, Apache-2.0, EPL-2.0 | formatting and the coverage floor |

Images, pinned by tag and digest (Docker Hub, 12 November 2026, arm64 included):
`postgres:18.6-trixie@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42`
(PostgreSQL License) with the package `pgbackrest=2.60.0-1.pgdg13+1` (MIT) from
apt.postgresql.org, and
`rustfs/rustfs:1.0.5@sha256:b81d16a90ee25bd9511280127ca1471788c6e874cf0f0da759645f46b2dc1d74`
(Apache-2.0). Why RustFS and not MinIO, and how pgBackRest was tested against it:
`docs/backup-tool-and-store.md`.

## Build and test (no Docker)

```text
$ ./gradlew clean check
> Task :processTestResources
> Task :testClasses
> Task :test
> Task :jacocoTestCoverageVerification
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport
BUILD SUCCESSFUL in 27s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
```

```text
tests 43, failed 0
line coverage 91.4 percent (1111 of 1215 lines; the stack package is covered by the stack tests)
```

Repeats, the 40 runs beside a second build, and the long and spaced paths:

```text
pitrDrill: second run identical
objectives: second run identical
chaos: second run identical
runbookCheck: second run identical
lab1Run: second run identical
lab2Run: second run identical
test task run 40 times beside a second build: 0 failures
./gradlew check from a path of 415 characters: passed
./gradlew check from a folder whose name has a space: passed
```

## The programs

One Gradle task each; the ones that write files use a folder under `build/work`, emptied first.

| Task | Program |
|---|---|
| `./gradlew -q pitrDrill` | four point-in-time restores on real files, each checked |
| `./gradlew -q objectives` | RPO and RTO per tier from the mechanism, and the break-even |
| `./gradlew -q restoreDrillModel` | a timed restore on this machine's disk and the size model |
| `./gradlew -q chaos` | the primary-kill experiment, its failures and the game day |
| `./gradlew -q runbookCheck`, `runbookCheckRegionB` | the recovery plan checked against the loss of each region |
| `./gradlew -q lab1Run`, `lab2Run`, `lab3Run` | the three lab solutions |

```text
$ ./gradlew -q pitrDrill
POINT-IN-TIME RESTORE DRILLS on real files (times UTC on 10 November 2026; segments of 64 records or 60 s, framed with CRC32C)
base backups at 09:00:00 and 10:00:00; bad DELETE of section A at 10:41:07

1. Undo the bad delete: restore the 10:00 backup to just before 10:41:07
   archive: 113 segments, 2696 records; 0 records never archived
   restore: replay ended at REACHED_TARGET
   recovered 1553 booking rows; last change replayed LSN 2039 at 10:41:05; newest heartbeat 10:41:00
   truth at 10:41:07: 1553 rows; missing from the restore: 0
   rows of the bad delete brought back: 517 of 517
   not in the restored copy: 117 bookings made after 10:41:07 (still in the archive and in production)
   checker: DECLARED GOOD, 1553 rows match the manifest's count and SHA-256

2. The same restore with segment 0000000000000080.seg (the one holding 10:20:00) missing from the archive
   archive: 113 segments, 2696 records; 0 records never archived
   restore: STOPPED SHORT, the log jumps from LSN 1249 to 1286: a segment between 0000000000000079.seg and 0000000000000081.seg is missing
   recovered 895 booking rows; last change replayed LSN 1249 at 10:19:09; newest heartbeat 10:19:00
   truth at 10:41:07: 1553 rows; missing from the restore: 658
   rows of the bad delete brought back: 298 of 517
   not in the restored copy: 117 bookings made after 10:41:07 (still in the archive and in production)
   checker: REFUSED, replay stopped short: the log jumps from LSN 1249 to 1286: a segment between 0000000000000079.seg and 0000000000000081.seg is missing; row count 895, manifest says 1553; row checksum differs from the manifest

3. The 10:00 backup was never test-restored; one byte of it is damaged
   archive: 113 segments, 2696 records; 0 records never archived
   restore: REFUSED, bookings.tsv does not match the manifest (size or SHA-256)
   checker: REFUSED, backup rejected: bookings.tsv does not match the manifest (size or SHA-256)
   fall back to the 09:00 backup and replay one more hour of log:
   archive: 113 segments, 2696 records; 0 records never archived
   restore: replay ended at REACHED_TARGET
   recovered 1553 booking rows; last change replayed LSN 2039 at 10:41:05; newest heartbeat 10:41:00
   truth at 10:41:07: 1553 rows; missing from the restore: 0
   rows of the bad delete brought back: 517 of 517
   not in the restored copy: 117 bookings made after 10:41:07 (still in the archive and in production)
   checker: DECLARED GOOD, 1553 rows match the manifest's count and SHA-256

4. region-a lost at 10:43:30; restore everything that reached the archive in region-b
   archive: 103 segments, 2113 records; 12 records never archived
   restore: replay ended at END_OF_ARCHIVE
   recovered 1615 booking rows; last change replayed LSN 2113 at 10:43:09; newest heartbeat 10:43:00
   truth at 10:43:30: 1625 rows; missing from the restore: 10
   lost with region-a: 10 bookings; data loss window 21 s by the last replayed commit, 30 s by the heartbeat
   bound from the mechanism: archive timeout 60 s + copy lag 5 s = 65 s
   checker: DECLARED GOOD, 1615 rows match the manifest's count and SHA-256
```

The other programs' outputs are in Module 40, Sections 8 and 11.

## The lab

Every script prints its plan and changes nothing unless given `--run`; it then asks before acting
unless also given `--yes`.

```text
scripts/stack.sh up --run --yes          start the store and the primary, create the bucket and the stanza
./gradlew stackTest                      the stack tests (or scripts/stack.sh test --run --yes: up, tests, down)
scripts/demo-drills.sh --run --yes       a point-in-time restore, a missing segment found by the checker
scripts/restore-drill.sh <manifest> --before <time> | --end --run --yes   one timed drill
scripts/mutants.sh --with-stack --run    one rule removed at a time
SEATWAVE_MEASURE_LOCK=<lock folder> scripts/measure.sh drill|lag|kill --run --yes   the measured runs
scripts/stack.sh down --run --yes        remove everything, credentials included
```

`scripts/stack.sh up` generates the store's keys, the database passwords, the cipher passphrase and a
lab certificate authority into `.secrets/` (ignored by git); nothing secret is in an image, the
Compose file or an environment variable. Ports, all on 127.0.0.1: the store's S3 API 24000, the
primary 24010, the restore target 24011, the region-b copy 24012.

```text
$ scripts/stack.sh up --run --yes
plan: up the Compose project seatwave-recovery
  write .secrets (keys, passwords, passphrase, lab certificates) if missing; build the image;
  docker compose -p seatwave-recovery up -d --wait object-store primary; create bucket seatwave-backups; stanza-create; check
2026-11-12 21:02:51.023 P00   INFO: stanza-create command begin 2.60.0: (options as in pgbackrest.conf)
2026-11-12 21:02:51.032 P00   INFO: stanza-create for stanza 'seatwave' on repo1
2026-11-12 21:02:51.054 P00   INFO: stanza-create command end: completed successfully (34ms)
2026-11-12 21:02:51.478 P00   INFO: check command begin 2.60.0: (options as in pgbackrest.conf)
2026-11-12 21:02:51.484 P00   INFO: check repo1 configuration (primary)
2026-11-12 21:02:51.505 P00   INFO: check repo1 archive for WAL (primary)
2026-11-12 21:03:00.235 P00   INFO: WAL segment 000000010000000000000002 successfully archived to '/seatwave/archive/seatwave/18-1/0000000100000000/000000010000000000000002-2fb5c709d1122244dffa9163d37b49c886ab8a8b.zst' on repo1
2026-11-12 21:03:00.236 P00   INFO: check command end: completed successfully (8760ms)
```

The stack tests:

```text
$ ./gradlew stackTest
BackupVerificationOnPostgresTest > aDamagedNewestBackupIsNotTheNewestGoodOne() PASSED
ObjectLockOnStoreTest > refusesToDeleteAnArchivedVersionUnderRetention() PASSED
PilotLightOnPostgresTest > thePromotedCopyAcceptsANewConfirm() PASSED
PilotLightOnPostgresTest > theCopyHoldsEveryAcknowledgedConfirm() PASSED
PrimaryKillOnPostgresTest > keepsEveryAcknowledgedConfirmWhenThePrimaryIsKilledUnderLoad() PASSED
RestoreDrillOnPostgresTest > refusesARestoreCheckedAgainstAnEarlierMomentThanItStoppedAt() PASSED
RestoreDrillOnPostgresTest > refusesAReplayThatStoppedAtAMissingSegment() PASSED
RestoreDrillOnPostgresTest > refusesAReplayThatMeetsADamagedSegment() PASSED
RestoreDrillOnPostgresTest > restoresToAChosenMomentWithEveryConfirmBeforeItAndNoneAfter() PASSED
RestoreDrillOnPostgresTest > refusesAPointInTimeRestoreWhoseTargetLiesBeyondAMissingSegment() PASSED
BUILD SUCCESSFUL in 2m 16s
```

Each mutant removes one rule; every one makes at least one test fail:

```text
$ scripts/mutants.sh --with-stack --run
mutant 1: the checker declares a replay that stopped short good
  failing: ProgramOutputTest > pitrDrillPrintsTheFourRestoresTheModuleQuotes() FAILED
  failing: RecoveryInvariantTest > refusesAReplayThatStopsAtAMissingSegment() FAILED
  first failure: org.opentest4j.AssertionFailedError at ProgramOutputTest.java:21
mutant 2: an experiment with no abort condition is accepted
  failing: RecoveryInvariantTest > refusesAnExperimentWithoutAbortConditions() FAILED
  failing: ChaosExperimentCliTest > printsTheExperimentsTheModuleQuotes() FAILED
  first failure: java.lang.AssertionError at RecoveryInvariantTest.java:122
mutant 3: the drill store's restore stops after the target, not before it
  failing: ProgramOutputTest > pitrDrillPrintsTheFourRestoresTheModuleQuotes() FAILED
  failing: RecoveryInvariantTest > declaresARestoreGoodWhenItMatchesTheManifest() FAILED
  failing: LabProgramsTest > lab2BringsTheRowsBackOnlyFromATargetBeforeTheMistake() FAILED
  first failure: org.opentest4j.AssertionFailedError at ProgramOutputTest.java:21
mutant 4: the JDBC checker accepts a copy restored past its target
  failing: ConfirmCheckerTest > refusesACopyThatHoldsAConfirmCommittedExactlyAtTheTarget() FAILED
  first failure: org.opentest4j.AssertionFailedError at ConfirmCheckerTest.java:41
mutant 5: the JDBC checker accepts a copy whose replay stopped short
  failing: ConfirmCheckerTest > refusesACopyWithAHoleAndSaysTheLossIsNotATail() FAILED
  failing: ConfirmCheckerTest > refusesACopyThatStoppedShortAndSaysTheLossIsATail() FAILED
  first failure: java.lang.NullPointerException at ConfirmCheckerTest.java:63
mutant 6: a backup whose verification never ran counts as good
  failing: BackupHealthTest > hasNoAgeWhenNoBackupWasEverVerified() FAILED
  failing: BackupHealthTest > doesNotCountANewerBackupThatNobodyVerified() FAILED
  first failure: java.lang.AssertionError at BackupHealthTest.java:53
mutant 7: a backup file is restored without comparing it with the manifest
  failing: ProgramOutputTest > pitrDrillPrintsTheFourRestoresTheModuleQuotes() FAILED
  failing: RecoveryInvariantTest > refusesARestoreWhoseBackupFailsItsManifest() FAILED
  first failure: org.opentest4j.AssertionFailedError at ProgramOutputTest.java:21
mutant 8: a runbook step that waits for a later step is accepted
  failing: RunbookCheckCliTest > reportsAStepThatWaitsForALaterStep() FAILED
  failing: RunbookCheckCliTest > printsTheDraftAndTheFixedPlanAsTheModuleQuotes() FAILED
  first failure: org.opentest4j.AssertionFailedError at RunbookCheckCliTest.java:37
mutant 9: the promotion does not advance the sequences
  failing: PilotLightOnPostgresTest > thePromotedCopyAcceptsANewConfirm() FAILED
  first failure: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint "customers_pkey"
```

A point-in-time restore to a chosen moment, then the same archive with one segment hidden:

```text
$ scripts/demo-drills.sh --run --yes
plan: on seatwave-recovery, a full backup, 1200 confirms at 200 a second with a segment switch every
  1.5 s, then three drills: to the moment before confirm 601; to the end with one
  segment hidden; to the last confirm with the segment hidden. The primary is not changed.
$ pgbackrest --stanza=seatwave --type=full backup
new backup label = 20261112-210736F
backup command end: completed successfully (4008ms)
$ ConfirmLoadCli 1200 200 build/demo/manifest.txt   (a segment switch every 1.5 s meanwhile)
planned 1,200 confirms at 200 a second for event 1794517661118: sent 1,200; acknowledged 1,200, uncertain 0, not sent 0
acknowledged confirm latency: p50 4.3 ms, p99 198.6 ms, max 215.2 ms
failures: none (every confirm sent was acknowledged)
manifest written: manifest.txt
$ scripts/restore-drill.sh build/demo/manifest.txt --before 2026-11-12T21:07:44.379815Z --run --yes   (the commit time of confirm 601)
drill of seatwave-recovery: --before 2026-11-12T21:07:44.379815Z, manifest manifest.txt
create target	21:07:53.334	0.885s
  repo1: restore backup set 20261112-210736F, recovery will start at 2026-11-12 21:07:36
  restore size = 36MB, file total = 1289
  restore command end: completed successfully (1801ms)
restore	21:07:54.282	2.881s
  2026-11-12 21:07:58.326 UTC [32] LOG:  recovery stopping before commit of transaction 12015, time 2026-11-12 21:07:44.379815+00
  2026-11-12 21:07:58.326 UTC [32] LOG:  redo done at 0/2404A6F8 system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.20 s
  2026-11-12 21:07:58.326 UTC [32] LOG:  last completed transaction was at log time 2026-11-12 21:07:44.379718+00
  2026-11-12 21:07:58.341 UTC [32] LOG:  selected new timeline ID: 2
replay	21:07:57.225	2.044s
  restore holds 600 rows of event 1794517661118; the manifest lists 1,200 acknowledged and 0 uncertain confirms; newest heartbeat 2026-11-12T21:07:43.562434Z
  newest acknowledged confirm present committed at 2026-11-12T21:07:44.379718Z
  checker: DECLARED GOOD, 600 acknowledged confirms present and 600 absent as expected, 0 uncertain ones committed
verify	21:07:59.333	0.374s
total	6.474s	RTO 3600s	PASS
$ hide archived segment 000000010000000000000024 (an S3 DELETE: in the locked bucket, a delete marker): HTTP 204
$ scripts/restore-drill.sh build/demo/manifest.txt --end --run --yes
drill of seatwave-recovery: --everything, manifest manifest.txt
create target	21:08:00.716	1.363s
  repo1: restore backup set 20261112-210736F, recovery will start at 2026-11-12 21:07:36
  restore size = 36MB, file total = 1289
  restore command end: completed successfully (1642ms)
restore	21:08:02.140	2.674s
  2026-11-12 21:08:06.596 UTC [32] LOG:  redo done at 0/23164C10 system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.25 s
  2026-11-12 21:08:06.596 UTC [32] LOG:  last completed transaction was at log time 2026-11-12 21:07:43.354966+00
  2026-11-12 21:08:07.263 UTC [32] LOG:  selected new timeline ID: 2
replay	21:08:04.880	2.906s
  restore holds 401 rows of event 1794517661118; the manifest lists 1,200 acknowledged and 0 uncertain confirms; newest heartbeat 2026-11-12T21:07:42.558301Z
  newest acknowledged confirm present committed at 2026-11-12T21:07:43.354966Z
  checker: REFUSED, 799 acknowledged confirms are missing
  missing: 799 acknowledged confirms committed from 2026-11-12T21:07:43.375175Z; all after the newest one present (the copy stops early)
verify	21:08:07.848	0.347s	FAILED
$ scripts/restore-drill.sh build/demo/manifest.txt --before 2026-11-12T21:07:47.371646Z --run --yes   (beyond the hidden segment)
drill of seatwave-recovery: --before 2026-11-12T21:07:47.371646Z, manifest manifest.txt
create target	21:08:08.921	1.614s
  repo1: restore backup set 20261112-210736F, recovery will start at 2026-11-12 21:07:36
  restore size = 36MB, file total = 1289
  restore command end: completed successfully (1671ms)
restore	21:08:10.598	2.725s
REFUSED: the restored server stopped during replay:
  2026-11-12 21:08:14.684 UTC [32] LOG:  redo starts at 0/22000028
  2026-11-12 21:08:14.731 UTC [32] LOG:  completed backup recovery with redo LSN 0/22000028 and end LSN 0/22000120
  2026-11-12 21:08:14.959 UTC [32] LOG:  redo done at 0/23164C10 system usage: CPU: user: 0.00 s, system: 0.00 s, elapsed: 0.27 s
  2026-11-12 21:08:14.959 UTC [32] FATAL:  recovery ended before configured recovery target was reached
replay	21:08:13.395	2.440s	FAILED
```

The measured drill (a 1,600 MB database, under the measurement lock; the load average is
printed beside each step):

```text
$ scripts/measure.sh drill --run --yes   (under the measurement lock)
phase drill started  [load average 10.74, 15.17, 14.72]
PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)
loading 5000000 bookings and their customers by SQL (one statement each)
INSERT 0 5000000
INSERT 0 5000000
CHECKPOINT
database size: 1600493247 bytes (1526 MB)
full backup, process-max 1  [load average 9.66, 13.77, 14.22]
  full backup size = 1.5GB, file total = 1286
  backup command end: completed successfully (11961ms)
full backup, process-max 4  [load average 9.19, 13.45, 14.10]
  full backup size = 1.5GB, file total = 1286
  backup command end: completed successfully (6081ms)
verify, process-max 4  [load average 8.86, 13.31, 14.05]
                                      backup: 20261112-204136F, status: valid, total files checked: 1286, total valid files: 1286
                                      backup: 20261112-204148F, status: valid, total files checked: 1286, total valid files: 1286
status: ok
  backup: 20261112-204136F, status: valid, total files checked: 1286, total valid files: 1286
  backup: 20261112-204148F, status: valid, total files checked: 1286, total valid files: 1286
  verify command end: completed successfully (4337ms)
writing about 1 GB of log by SQL after the backup
INSERT 0 2500000
INSERT 0 2500000
log written after the backup: 1551171312 bytes
planned 2,000 confirms at 200 a second for event 1794516143925: sent 2,000; acknowledged 2,000, uncertain 0, not sent 0
acknowledged confirm latency: p50 4.4 ms, p99 160.1 ms, max 179.5 ms
failures: none (every confirm sent was acknowledged)
manifest written: drill-manifest.txt
check: switch and wait for the archive  [load average 11.39, 13.34, 14.02]
  WAL segment 000000010000000100000061 successfully archived to '/seatwave/archive/seatwave/18-1/0000000100000001/000000010000000100000061-cadb6b27d939586d740b8bc3d1d9ed75d9677225.zst' on repo1
the drill: newest backup, replay to the end of the archive, the checker  [load average 12.72, 13.58, 14.10]
  restore holds 2,000 rows of event 1794516143925; the manifest lists 2,000 acknowledged and 0 uncertain confirms; newest heartbeat 2026-11-12T20:42:33.277394Z
  newest acknowledged confirm present committed at 2026-11-12T20:42:34.078897Z
  checker: DECLARED GOOD, 2,000 acknowledged confirms present and 0 absent as expected, 0 uncertain ones committed
drill of seatwave-recovery: --everything, manifest drill-manifest.txt
create target	20:42:44.222	0.655s
  repo1: restore backup set 20261112-204148F, recovery will start at 2026-11-12 20:41:48
  restore size = 1.5GB, file total = 1286
  restore command end: completed successfully (3612ms)
restore	20:42:44.941	4.806s
  2026-11-12 20:43:29.916 UTC [32] LOG:  redo done at 1/ACFFD2D8 system usage: CPU: user: 5.25 s, system: 4.11 s, elapsed: 39.22 s
  2026-11-12 20:43:29.916 UTC [32] LOG:  last completed transaction was at log time 2026-11-12 20:43:01.39336+00
  2026-11-12 20:43:30.574 UTC [32] LOG:  selected new timeline ID: 2
replay	20:42:49.809	41.908s
  restore holds 2,000 rows of event 1794516143925; the manifest lists 2,000 acknowledged and 0 uncertain confirms; newest heartbeat 2026-11-12T20:42:33.277394Z
  newest acknowledged confirm present committed at 2026-11-12T20:42:34.078897Z
  checker: DECLARED GOOD, 2,000 acknowledged confirms present and 0 absent as expected, 0 uncertain ones committed
verify	20:43:31.780	0.399s
total	48.042s	RTO 3600s	PASS
replayed log: 2986361944 bytes from the backup start to the end of replay
restore only, process-max 1, into a fresh target  [load average 12.66, 13.44, 14.02]
  restore size = 1.5GB, file total = 1286
  restore command end: completed successfully (7300ms)
phase drill ended  [load average 12.83, 13.44, 14.01]
```

The archive's lag and the pilot light, and Lab 3's primary kill, are in `measurements/` after a run
and in Module 40, Section 8, Commands 10 and 11.

Clean-up:

```text
$ scripts/stack.sh down --run --yes
plan: down the Compose project seatwave-recovery
  docker compose -p seatwave-recovery --profile drill --profile pilot-light down -v --remove-orphans; remove .secrets; check nothing is left
nothing of seatwave-recovery is left
$ docker ps -a, docker network ls, docker volume ls, filtered by the project label
docker ps -a: 0 left
docker network ls: 0 left
docker volume ls: 0 left
```

## Project structure

See `ARCHITECTURE.md` for the packages, the data flow, the decisions and the invariants.

## What is simplified

- One machine stands in for two regions; the store, the primary, the restore target and the copy
  share one virtual disk and network, so rates and lags here are this laptop's.
- The store is one RustFS node with one drive; Module 29 rehearses the store's own redundancy.
- The roles are simpler than Module 42's: an owner, an application login and a replication login;
  the lab's superuser runs pgBackRest and manages the subscription.
- The load generator writes a customer, a booking and an outbox row per confirm; there are no holds,
  payments or events.
- The cloud steps (an RDS point-in-time restore, a forced failover, a zonal shift) and logical
  replication across regions need an AWS account and two regions, and are not run.

## No CI on purpose

The course keeps its projects without a hosted CI workflow; every check here runs locally with the
commands above.

## License

MIT, see `LICENSE`.
