# Outils i18n (RIC-24, socle posé au lot 0 / RIC-187, achevé au lot 4 / RIC-191)

Trois scripts, aucune dépendance externe (Python 3 standard, Bash + coreutils).

## Régénérer les ressources de chaînes

Source de vérité : le dernier `docs/pilotage/i18n/strings-inventaire-vN.csv` (hors dépôt, `docs/`
est ignoré par git). Une correction se fait dans l'inventaire, jamais directement dans les
`strings.xml` générés (ils portent un en-tête "NE PAS ÉDITER À LA MAIN", qui cite le CSV
réellement utilisé pour la dernière génération).

```bash
python3 tools/i18n/generate_strings.py docs/pilotage/i18n/strings-inventaire-v8.csv
```

Écrit `app/src/main/res/values/strings.xml` (anglais, langue par défaut) et
`app/src/main/res/values-fr/strings.xml` (français). Idempotent : deux exécutions sur le même
CSV produisent des fichiers identiques. Sort en erreur (code 1) sur une clé dupliquée, un jeu de
paramètres `%n$s`/`%n$d` incohérent entre français et anglais, ou une forme plurielle "other"
manquante, le détail des erreurs sortant sur stderr. Aucune donnée hors `app_name` (ressource
préexistante fixée par le générateur, absente de l'inventaire) ne vit dans le script : une
correction de contenu se fait toujours dans le CSV, jamais dans `generate_strings.py`.

### La quantité `many` du français (RIC-191)

Le générateur ajoute lui-même, **pour `values-fr` uniquement**, une forme `many` identique à
`other`. Ce n'est pas une donnée de l'inventaire : l'inventaire porte les mêmes quantités dans les
deux langues, ce que le générateur continue d'exiger.

La raison : Lint (`MissingQuantity`) réclame les quantités que CLDR déclare pour la langue du
dossier, et le français en compte une de plus que l'anglais. Cette quantité `many` ne couvre en
français que les multiples d'un million écrits en toutes lettres (« un million de photos »),
aucune valeur qu'une app comptant des photos, des fichiers et des sorties puisse produire. La
forme identique à `other` n'est donc pas une approximation, c'est le seul rendu correct pour les
valeurs atteignables, et elle retire les 29 avertissements qui masquaient le reste. Elle n'est
jamais ajoutée à l'anglais, où CLDR ne connaît que `one` et `other`.

`StringsResourcesConsistencyTest` vérifie de son côté que chaque `<plurals>` français la porte,
pour qu'une régénération oubliée se voie.

### Tests du générateur

Pas des tests JVM, le script est du Python pur. Ils s'exécutent **depuis `tools/i18n/`**, parce que
`test_generate_strings.py` importe `generate_strings` comme un module voisin ; la commande
documentée jusqu'ici, lancée depuis la racine, échouait sur un `ModuleNotFoundError` :

```bash
cd tools/i18n && python3 -m unittest test_generate_strings
```

## Vérifier ce qui reste en dur

```bash
tools/i18n/check_hardcoded.sh            # garde-fou bloquant : code 1 s'il reste un littéral
tools/i18n/check_hardcoded.sh --no-fail  # relevé seul, toujours code 0
tools/i18n/check_hardcoded.sh --quiet    # totaux par fichier, sans le détail ligne à ligne
```

Liste, par fichier et par numéro de ligne, les littéraux Kotlin qui ressemblent à du texte français
(lettre accentuée, guillemet français, ou mot français fréquent), dans `ui/` et les packages métier
`journal/`, `gpximport/`, `bilan/`, `settings/`, plus les quatre fichiers de `data/` qui portent du
texte lu par l'utilisateur (`BackupManager`, `SystemTag`, `TrekDatesFormatter`,
`ExclusiveOperations`). Le reste de `data/` est du stockage, des DAO Room et du parsing GPX : ses
chaînes ne sont pas de l'IHM et n'ont rien à faire dans l'inventaire.

**Depuis le lot 4, c'est un garde-fou BLOQUANT** : code de retour 1 dès qu'un littéral suspect
subsiste. Il n'a plus aucune exception, les quatre lots étant passés : `TEMPORARY_ALLOWLIST` est
vide, et `--allow CHEMIN` (cumulable) reste disponible pour un chantier futur qui migrerait à
nouveau écran par écran.

Le contrôle vit dans `check_hardcoded.py` ; le `.sh` n'est plus qu'un point d'entrée. La version
lot 0 était un `sed | grep` qui **sous-comptait gravement** et donnait des numéros de ligne faux :
elle effaçait les commentaires de bloc avec un `.*` gourmand appliqué au fichier entier recollé en
une seule ligne, donc tout ce qui se trouvait entre le premier `/*` et le dernier `*/`. Sur un
fichier normalement commenté, il ne restait presque rien à inspecter : `JournalViewModel.kt`, qui
portait une dizaine de messages en dur, en ressortait à zéro. Mesuré avec le script corrigé, le
chantier est passé de **202 littéraux** (avant les lots 3 et 4) à **57** (lot 3 mergé) puis à **0**.

La version actuelle découpe vraiment le source Kotlin, ligne à ligne : commentaires de ligne,
commentaires de bloc imbriqués, chaînes simples et chaînes brutes `"""`. Elle ignore en outre les
appels `Log.*` (multi-ligne compris : ces messages s'adressent à qui lit un logcat, pas à
l'utilisateur) et les fragments SQL. Elle reste un heuristique sur le FRANÇAIS, pas un parseur
complet : un littéral anglais contenant « les » ou « est » sera signalé, un message français sans
accent ni mot courant sera raté.

### Marquer un littéral délibérément non traduit

```kotlin
// i18n-ok : contenu d'un fichier de l'archive, pas un texte d'IHM (RIC-191).
"# Sauvegarde Bivouac. Ne pas modifier : ..." +
    "# une archive tronquée avant ..."
```

Le marqueur `i18n-ok` vaut sur sa propre ligne, et remonte à travers les lignes qui précèdent tant
qu'elles sont des commentaires ou qu'elles se terminent par un `+` : les morceaux d'une même
concaténation se marquent donc une seule fois. Deux usages dans le code aujourd'hui : l'en-tête du
manifeste écrit *dans* l'archive de sauvegarde, et le message d'une `IOException` interne de
`GpxImportViewModel` qui ne remonte qu'au logcat.

## Garde-fous Lint (`app/build.gradle.kts`)

- `error += "MissingTranslation"` : une ressource ajoutée dans une langue sans son équivalent dans
  l'autre casse la build, dès le lot 0 (avant même qu'un écran ne consomme ces ressources).
- `warning += "ImpliedQuantity"` : trois `<plurals>` de l'inventaire le déclenchent par conception,
  voir le commentaire à côté dans `build.gradle.kts`.
