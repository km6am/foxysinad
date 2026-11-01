#!/bin/sh
# Is the forked analysis still in step with foxsinad? Lists foxsinad commits
# since the fork that touch the forked code, and diffs the copied sinad_meter
# files against foxsinad's current ones. Silent apart from the headings = in step.
FOXSINAD=${FOXSINAD_REPO:-$HOME/foxsinad}
PY=app/src/main/python
BASE=$(sed -n 's/^FORKED_FROM = "foxsinad \([0-9a-f]*\),.*/\1/p' $PY/analysis.py)
echo "== foxsinad commits since $BASE touching the forked code"
git -C "$FOXSINAD" log --oneline "$BASE..HEAD" -- foxsinad/analyzer.py third_party/sinad_meter
echo "== sinad_meter copy vs $FOXSINAD/third_party/sinad_meter"
for f in filters.py sinad.py LICENSE vendored/__init__.py vendored/pysnr/__init__.py \
         vendored/pysnr/sinad.py vendored/pysnr/utils.py vendored/pysnr/LICENSE; do
    diff -u "$FOXSINAD/third_party/sinad_meter/$f" "$PY/sinad_meter/$f" || true
done
