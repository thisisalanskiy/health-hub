#!/bin/bash
# Dumps health_dashboard to /Volumes/Data/Health, keeps last 30 backups.
# Skips silently if the volume is not mounted.

DEST="/Volumes/Data/Health"
DB_URL="postgresql://hcuser:hcpassword@localhost:5432/health_dashboard"
PG_DUMP="/opt/homebrew/opt/postgresql@16/bin/pg_dump"
KEEP=30

if [[ ! -d "$DEST" ]]; then
    echo "$(date): $DEST not mounted, skipping backup." >&2
    exit 0
fi

FILE="$DEST/health_dashboard_$(date +%Y%m%d_%H%M%S).sql.gz"
$PG_DUMP "$DB_URL" | gzip > "$FILE"

if [[ $? -eq 0 ]]; then
    echo "$(date): Backup written to $FILE"
else
    echo "$(date): pg_dump failed." >&2
    rm -f "$FILE"
    exit 1
fi

# Rotate: delete oldest beyond KEEP
ls -1t "$DEST"/health_dashboard_*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs rm -f --
