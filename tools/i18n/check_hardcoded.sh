#!/usr/bin/env bash
# RIC-187 (lot 0 du chantier i18n RIC-24) : releve, par fichier, les littéraux Kotlin qui
# ressemblent encore à du texte français en dur -- ligne de base des lots 1 à 4, qui migreront ces
# écrans un par un vers stringResource(). Volontairement un heuristique grep, pas un vrai parseur
# Kotlin : il rate de vrais cas (mot anglais qui ressemble à un mot français, concaténation étalée
# sur plusieurs lignes) et en signale de faux (un commentaire mal détecté, un identifiant qui
# contient une lettre accentuée dans une chaîne technique) -- ce n'est pas son rôle d'être exact au
# caractère près, seulement de donner un ORDRE DE GRANDEUR qui décroît lot après lot.
#
# Usage :
#   tools/i18n/check_hardcoded.sh
#
# Toujours code de retour 0 pour l'instant (RIC-187) : ce script n'est PAS encore un garde-fou
# bloquant. Il le deviendra quand les lots 1 à 4 auront fait tendre son total vers zéro -- au
# premier plan, décider alors d'un seuil (probablement zéro, ou une liste d'exceptions explicite
# pour les cas légitimement non traduits comme l'attribution Esri, voir map_layer_satellite_
# attribution_text dans l'inventaire i18n).

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Les cinq racines citées par le kickoff RIC-187 : l'arbre ui/ (écrans Compose, tous sous-dossiers
# confondus) et les quatre packages métier de même nom qui vivent à côté de ui/, pas dessous
# (journal/, gpximport/, bilan/, settings/ -- ViewModels et messages construits hors Composable).
ROOTS=(
    "app/src/main/java/com/bivouac/app/ui"
    "app/src/main/java/com/bivouac/app/journal"
    "app/src/main/java/com/bivouac/app/gpximport"
    "app/src/main/java/com/bivouac/app/bilan"
    "app/src/main/java/com/bivouac/app/settings"
)

# Mots français très fréquents, choisis pour ne quasiment jamais apparaître dans du code ou des
# identifiants anglais légitimes (évite par exemple "car", "an", "ce" qui donneraient trop de faux
# positifs). Recherche insensible à la casse, sur des frontières de mot.
FRENCH_WORDS='\b(le|la|les|un|une|des|du|de|et|ou|est|sont|pour|avec|dans|sur|cette|ces|vous|votre|vos|sans|où|être|avoir|fait|peut|doit)\b'

# Lettre accentuée courante en français (le guillemet français « » est un signal encore plus sûr,
# ajouté séparément ci-dessous).
ACCENTED='[àâäéèêëïîôöùûüçÀÂÄÉÈÊËÏÎÔÖÙÛÜÇ]'

total=0

for root in "${ROOTS[@]}"; do
    dir="$repo_root/$root"
    [ -d "$dir" ] || continue

    while IFS= read -r -d '' file; do
        # Retire les commentaires de bloc et de ligne avant de chercher un littéral : une passe par
        # sed qui vide le contenu des /* ... */ (y compris sur plusieurs lignes, via une plage
        # d'adresses) et coupe tout ce qui suit un // en dehors d'une chaîne -- approximation
        # acceptable pour un heuristique (voir l'avertissement en tête de fichier).
        matches=$(sed -e ':a;N;$!ba' -e 's://[^\n]*::g' -e 's:/\*.*\*/::g' "$file" \
            | grep -nE "\"[^\"]*(${ACCENTED}|${FRENCH_WORDS}|«)[^\"]*\"" -i \
            || true)

        [ -z "$matches" ] && continue

        count=$(printf '%s\n' "$matches" | grep -c .)
        total=$((total + count))
        rel="${file#"$repo_root"/}"
        echo "$rel : $count"
        printf '%s\n' "$matches" | sed 's/^/    /'
    done < <(find "$dir" -name '*.kt' -print0 | sort -z)
done

echo "---"
echo "Total : $total littéraux suspects (base de départ des lots 1 à 4 de RIC-24)."

exit 0
