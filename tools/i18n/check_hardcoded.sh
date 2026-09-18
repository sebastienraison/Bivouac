#!/usr/bin/env bash
# RIC-187 puis RIC-191 : garde-fou des littéraux français restés en dur.
#
# Le contrôle lui-même vit dans check_hardcoded.py depuis RIC-191 : l'ancienne version en
# sed | grep sous-comptait gravement (elle effaçait tout ce qui se trouvait entre le premier /* et
# le dernier */ du fichier, donc presque tout le code) et donnait des numéros de ligne faux. Ce
# script reste le point d'entrée, parce que c'est lui qui est documenté et appelé ailleurs.
#
# Les arguments sont passés tels quels au script Python : --allow CHEMIN, --no-fail, --quiet.
# Code de retour 1 dès qu'un littéral suspect subsiste hors liste d'exclusion : garde-fou BLOQUANT
# depuis le lot 4.

set -euo pipefail

exec python3 "$(dirname "${BASH_SOURCE[0]}")/check_hardcoded.py" "$@"
