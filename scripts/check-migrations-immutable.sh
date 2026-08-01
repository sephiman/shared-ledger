#!/usr/bin/env sh
#
# Flyway records a checksum for every migration it applies and refuses to start if the file
# changed afterwards — a reworded comment is enough, since the checksum covers the raw file
# line by line, so an edit crash-loops every environment that already ran it.
#
# So: a migration that already exists in the base revision is frozen. Adding new ones is fine.
#
# Usage:
#   check-migrations-immutable.sh              # staged changes vs HEAD (pre-commit)
#   check-migrations-immutable.sh <base-ref>   # HEAD vs <base-ref>   (CI)
set -eu

MIGRATIONS=backend/src/main/resources/db/migration

if [ $# -ge 1 ]; then
    BASE=$1
    # Renames and deletes matter too: Flyway keys history on the version, so renaming
    # V030__a.sql to V030__b.sql leaves the applied row pointing at a file that is gone.
    CHANGED=$(git diff --name-only --diff-filter=MDR "$BASE"...HEAD -- "$MIGRATIONS" || true)
    CONTEXT="modified relative to $BASE"
else
    CHANGED=$(git diff --cached --name-only --diff-filter=MDR -- "$MIGRATIONS" || true)
    CONTEXT="staged for commit"
fi

[ -z "$CHANGED" ] && exit 0

echo "✖ Applied migrations must never change — they are already recorded in deployed databases."
echo ""
echo "  These are $CONTEXT:"
echo "$CHANGED" | sed 's/^/    /'
echo ""
echo "  Even a comment or whitespace edit changes the Flyway checksum and will crash-loop"
echo "  every environment that already ran the migration."
echo ""
echo "  To change the schema, add a new migration instead."
echo "  If the edit is genuinely cosmetic and no environment has applied it yet, revert it:"
echo "    git checkout -- $MIGRATIONS"
exit 1
