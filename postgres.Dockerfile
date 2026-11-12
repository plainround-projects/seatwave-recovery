# PostgreSQL 18.6 with pgBackRest 2.60.0 for the seatwave-recovery lab (Module 40).
# The base is the Docker Official Image, pinned by tag and digest (checked on Docker Hub, 12
# November 2026). It already trusts the PostgreSQL project's Debian repository, whose package
# 2.60.0-1.pgdg13+1 is pgBackRest's current release (checked on apt.postgresql.org and on
# pgBackRest's GitHub releases, 12 November 2026), so the tool is installed as a pinned package
# instead of being built from source. PostgreSQL itself runs as the image's postgres user.
FROM postgres:18.6-trixie@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42
RUN apt-get update \
 && apt-get install -y --no-install-recommends pgbackrest=2.60.0-1.pgdg13+1 \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /var/spool/pgbackrest /var/log/pgbackrest \
 && chown postgres:postgres /var/spool/pgbackrest /var/log/pgbackrest
