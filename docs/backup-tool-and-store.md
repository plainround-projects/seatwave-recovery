# The backup tool and the lab's object store

Checked on 12 November 2026.

## Why the question came up

Module 40's lab was first written with pgBackRest writing its repository to MinIO, built from source
as Module 29's first lab did. On 11 November 2026 MinIO's community edition was found archived and
source-only. The course owner decided on 12 November 2026 to replace it with a maintained
S3-compatible store, and Module 29's build chose RustFS 1.0.5
(`rustfs/rustfs:1.0.5@sha256:b81d16a90ee25bd9511280127ca1471788c6e874cf0f0da759645f46b2dc1d74`). That
build tested RustFS with the AWS SDK for Java and the AWS CLI, not with a PostgreSQL backup tool, so
this project tested pgBackRest against it first.

## What pgBackRest needs from an S3 store

From pgBackRest's configuration reference (pgbackrest.org/configuration.html, 12 November 2026):

- `repo1-type=s3` with `repo1-s3-endpoint`, `repo1-storage-port`, `repo1-s3-bucket`,
  `repo1-s3-region` and `repo1-s3-uri-style` (`host`, the default, or `path`).
- TLS: the reference offers `repo1-storage-verify-tls`, `repo1-storage-ca-file` and
  `repo1-storage-ca-path` for custom and test stores, and no option for plain HTTP. The repository is
  reached over TLS only.
- Client-side encryption with `repo1-cipher-type=aes-256-cbc` and a passphrase.

## What was run

PostgreSQL 18.6 (`postgres:18.6-trixie`) with pgBackRest 2.60.0 from the PostgreSQL project's Debian
repository (package `2.60.0-1.pgdg13+1`), against one RustFS 1.0.5 node with TLS turned on
(`RUSTFS_TLS_PATH`, a certificate from a throwaway lab authority), on Docker 24.0.6 on an
Apple-silicon laptop.

| Check | Result |
|---|---|
| `stanza-create`, path-style, region `us-east-1`, TLS with the lab authority verified | completed successfully |
| `check` (forces a segment switch, waits for it in the repository) | segment archived |
| full backup of 254 MB of incompressible data, encrypted | completed; one object of 205 MB, so pgBackRest's multipart upload (5 MiB parts) worked |
| `verify` | completed; every file valid |
| restore to a chosen moment with `--target-exclusive`, then start | the row committed before the moment present, the one after absent |
| request bodies | pgBackRest signed whole payloads (`x-amz-content-sha256` set to the payload's hash); no `aws-chunked` body was sent, so the chunked-encoding problem Module 29 met with the AWS SDK does not arise |
| bucket with Object Lock, default COMPLIANCE retention of 1 day | `stanza-create` and a full backup completed; deleting a locked version was refused (403, "Object is under COMPLIANCE retention"); a plain delete only added a delete marker |

The project's stack tests then ran the drill, a missing and a damaged log segment, a damaged
backup, the primary killed under load and the pilot-light promotion against this pair (see the
README).

## Found while testing

- `pgbackrest verify` exited with status 0 when it found a damaged backup file or archived segment;
  its text output said `status: error` and named the file. A job that judges a backup by the exit
  status of verify would count a damaged backup as good, so the project reads the per-backup status
  that `verify --output=text --verbose` prints (`PgBackRestInfo.verified`).

## The decision

pgBackRest 2.60.0 stays, installed as the pinned distribution package rather than built from source,
with its repository on RustFS 1.0.5 over TLS. No other backup tool was needed, so none was compared.
The lab's bucket is created with Object Lock and COMPLIANCE retention, the rule Section 10 of the
module gives production (35 days there, 1 day in the lab).
