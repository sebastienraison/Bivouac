# Outils i18n (RIC-24, socle posé au lot 0 / RIC-187)

Deux scripts, aucune dépendance externe (Python 3 standard, Bash + coreutils).

## Régénérer les ressources de chaînes

Source de vérité : `docs/pilotage/i18n/strings-inventaire-v4.csv` (hors dépôt, `docs/` est
ignoré par git). Une correction se fait dans l'inventaire, jamais directement dans les
`strings.xml` générés (ils portent un en-tête "NE PAS ÉDITER À LA MAIN").

```bash
python3 tools/i18n/generate_strings.py docs/pilotage/i18n/strings-inventaire-v4.csv
```

Écrit `app/src/main/res/values/strings.xml` (anglais, langue par défaut) et
`app/src/main/res/values-fr/strings.xml` (français). Idempotent : deux exécutions sur le même
CSV produisent des fichiers identiques. Sort en erreur (code 1) sur une clé dupliquée, un jeu de
paramètres `%n$s`/`%n$d` incohérent entre français et anglais, ou une forme plurielle "other"
manquante, le détail des erreurs sortant sur stderr. Aucune donnée hors `app_name` (ressource
préexistante fixée par le générateur, absente de l'inventaire) ne vit dans le script : une
correction de contenu se fait toujours dans le CSV, jamais dans `generate_strings.py`.

Tests du générateur (pas des tests JVM, le script est du Python pur) :

```bash
python3 -m unittest tools/i18n/test_generate_strings.py
```

## Vérifier ce qui reste en dur

```bash
tools/i18n/check_hardcoded.sh
```

Liste, par fichier, les littéraux Kotlin qui ressemblent à du texte français (lettre accentuée ou
mot français fréquent), dans `ui/` et les packages métier `journal/`, `gpximport/`, `bilan/`,
`settings/`. Un heuristique grep, pas un vrai parseur Kotlin : il rate de vrais cas et en signale
de faux, il donne un ordre de grandeur, pas un chiffre exact. Sort toujours en 0 pour l'instant
(RIC-187, lot 0) : les lots 1 à 4 feront tendre son total vers zéro écran par écran, et lui seul
deviendra alors un vrai garde-fou bloquant.

## Garde-fous Lint (`app/build.gradle.kts`)

- `error += "MissingTranslation"` : une ressource ajoutée dans une langue sans son équivalent dans
  l'autre casse la build, dès le lot 0 (avant même qu'un écran ne consomme ces ressources).
- `warning += "ImpliedQuantity"` : deux `<plurals>` de l'inventaire (contentDescription sans aucun
  chiffre dans le texte, le compte étant affiché à côté) déclenchent ce contrôle par conception,
  voir le commentaire à côté dans `build.gradle.kts`.
