#!/usr/bin/env bash
# RIC-206 : garde-fou de fin de lot -- verifie que les strings.xml committes correspondent bien a
# une regeneration fraiche depuis l'inventaire donne.
#
# Un lot recent a modifie strings.xml a la main sans reporter la correction dans l'inventaire :
# une regeneration ulterieure aurait annule cette correction en silence. Ce script regenere dans
# un repertoire temporaire (jamais dans app/src/main/res, via generate_strings.py --out-dir) et
# compare octet a octet aux deux fichiers committes.
#
# Usage :
#     tools/i18n/check_generated.sh chemin/vers/inventaire.csv
#
# Code de retour : 1 si un ecart existe (edition manuelle non reportee, regeneration oubliee,
# inventaire modifie sans regeneration...), 0 si les fichiers committes correspondent exactement
# a une regeneration depuis ce CSV.
#
# Le CSV doit etre le meme, sous la meme forme (absolue ou relative au depot), que celui utilise
# pour la derniere generation committee : l'en-tete "Source : ..." des fichiers generes en depend,
# un chemin ecrit differemment (relatif vs absolu) produirait un ecart sur cette seule ligne.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage : $0 chemin/vers/inventaire.csv" >&2
    exit 2
fi

CSV_PATH="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ ! -f "$CSV_PATH" ]]; then
    echo "Introuvable : $CSV_PATH" >&2
    exit 2
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

python3 "$SCRIPT_DIR/generate_strings.py" "$CSV_PATH" --out-dir "$TMP_DIR"

STATUS=0

for LANG_DIR in values values-fr; do
    GENERATED="$TMP_DIR/$LANG_DIR/strings.xml"
    COMMITTED="$REPO_ROOT/app/src/main/res/$LANG_DIR/strings.xml"
    if ! diff -u "$COMMITTED" "$GENERATED" > "$TMP_DIR/diff-$LANG_DIR.txt"; then
        STATUS=1
        echo "=== Ecart dans $LANG_DIR/strings.xml (committe vs regenere depuis $CSV_PATH) ==="
        cat "$TMP_DIR/diff-$LANG_DIR.txt"
        echo
    fi
done

if [[ $STATUS -eq 0 ]]; then
    echo "check_generated : OK, les deux strings.xml correspondent a une regeneration depuis $CSV_PATH."
else
    echo "check_generated : ECART -- reporter la correction dans l'inventaire, puis regenerer (voir README)." >&2
fi

exit $STATUS
