#!/usr/bin/env python3
"""RIC-191 (lot 4 du chantier i18n RIC-24) : garde-fou des littéraux français restés en dur.

Remplace l'heuristique `sed | grep` du lot 0 (RIC-187), qui sous-comptait gravement et donnait des
numéros de ligne faux : la suppression des commentaires de bloc s'y faisait sur le fichier entier
recollé en une seule ligne, avec un `.*` gourmand (`s:/\\*.*\\*/::g` après `:a;N;$!ba`). Tout ce qui
se trouvait entre le PREMIER `/*` et le DERNIER `*/` du fichier partait à la poubelle : sur un
fichier normalement commenté, c'est-à-dire presque tout le code de Bivouac, il ne restait plus
grand-chose à inspecter. JournalViewModel.kt, qui porte une dizaine de messages en dur, ressortait
ainsi à zéro littéral.

Ici : un vrai découpage du source Kotlin, ligne à ligne, qui distingue commentaires de ligne,
commentaires de bloc imbriqués, chaînes simples et chaînes brutes `\"\"\"`. Les numéros de ligne sont
donc exacts, et le total est comparable d'un lot à l'autre.

Reste un heuristique, pas un parseur complet : il juge du FRANÇAIS, pas du code. Un littéral anglais
qui contient « les » ou « est » sera signalé, un message français sans accent ni mot courant sera
raté. C'est assumé : le but est de ne rien laisser passer d'évident, pas de compter juste au
caractère près.

Usage :
    tools/i18n/check_hardcoded.py [--allow CHEMIN]... [--no-fail] [--quiet]

Code de retour : 1 dès qu'un littéral suspect subsiste hors liste d'exclusion, 0 sinon. C'est un
garde-fou BLOQUANT depuis le lot 4 (avant, il sortait toujours en 0).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Les cinq racines du lot 0 : l'arbre ui/ (écrans Compose) et les quatre packages métier de même nom
# qui vivent à côté de ui/, pas dessous (ViewModels et messages construits hors Composable).
SCAN_ROOTS = (
    "app/src/main/java/com/bivouac/app/ui",
    "app/src/main/java/com/bivouac/app/journal",
    "app/src/main/java/com/bivouac/app/gpximport",
    "app/src/main/java/com/bivouac/app/bilan",
    "app/src/main/java/com/bivouac/app/settings",
)

# RIC-191 : les quatre fichiers de data/ qui portent, eux aussi, du texte lu par l'utilisateur. Cités
# un par un et non par leur répertoire : le reste de data/ est du stockage, des DAO Room et du
# parsing GPX, dont les chaînes (SQL, noms de balises, clés de préférences) ne sont pas de l'IHM et
# n'ont rien à faire dans l'inventaire.
SCAN_FILES = (
    "app/src/main/java/com/bivouac/app/data/backup/BackupManager.kt",
    "app/src/main/java/com/bivouac/app/data/db/SystemTag.kt",
    "app/src/main/java/com/bivouac/app/data/model/TrekDatesFormatter.kt",
    "app/src/main/java/com/bivouac/app/data/operations/ExclusiveOperations.kt",
)

# RIC-191 : exclusion TEMPORAIRE, prévue pour les fichiers du lot 3 pendant qu'il tournait en
# parallèle. Le lot 3 (RIC-190) ayant été mergé avant la fin du lot 4, elle est VIDE : le garde-fou
# n'a plus aucune exception, et c'est ce qui le rend utile.
#
# La laisser en place, vide, plutôt que de la supprimer : c'est le seul endroit prévu pour une
# exception, et la voir vide dit que le chantier est allé au bout. L'option --allow reste là pour
# un chantier futur qui migrerait à nouveau écran par écran.
TEMPORARY_ALLOWLIST: tuple[str, ...] = ()

# Mots français très fréquents, choisis pour ne quasiment jamais apparaître dans du code ou des
# identifiants anglais légitimes (évite par exemple "car", "an", "ce" qui donneraient trop de faux
# positifs).
FRENCH_WORDS = (
    "le", "la", "les", "un", "une", "des", "du", "de", "et", "ou", "est", "sont", "pour", "avec",
    "dans", "sur", "cette", "ces", "vous", "votre", "vos", "sans", "où", "être", "avoir", "fait",
    "peut", "doit",
)
FRENCH_WORD_RE = re.compile(r"\b(" + "|".join(FRENCH_WORDS) + r")\b", re.IGNORECASE)
ACCENTED_RE = re.compile(r"[àâäéèêëïîôöùûüçÀÂÄÉÈÊËÏÎÔÖÙÛÜÇ«»]")

# Chaîne technique à ne jamais traduire : requête Room, fragment SQL, motif de format.
SQL_RE = re.compile(
    r"\b(SELECT|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+TABLE|PRAGMA|ORDER\s+BY)\b",
    re.IGNORECASE,
)

# Marqueur posé dans le code sur un littéral délibérément non traduit (contenu de fichier, format de
# données, nom propre) : `// i18n-ok : raison`, sur la ligne elle-même ou sur celle d'avant.
IGNORE_MARKER = "i18n-ok"

LOG_CALL_RE = re.compile(r"\bLog\.[a-z]+\s*\(")


class Literal:
    def __init__(self, line: int, text: str, source_line: str):
        self.line = line
        self.text = text
        self.source_line = source_line


def extract_literals(source: str) -> list[Literal]:
    """Découpe le source Kotlin et renvoie ses littéraux de chaîne, avec leur numéro de ligne.

    Gère : commentaire de ligne `//`, commentaire de bloc `/* */` IMBRIQUÉ (Kotlin l'autorise, et
    KDoc en pose partout), chaîne simple avec échappements, chaîne brute `\"\"\"`. Ignore les
    littéraux de caractère `'x'`, qui ne portent jamais de phrase.
    """
    literals: list[Literal] = []
    lines = source.split("\n")
    i = 0  # index de ligne (0-based)
    pos = 0  # index de colonne dans lines[i]
    block_depth = 0

    while i < len(lines):
        line = lines[i]
        if pos >= len(line):
            i += 1
            pos = 0
            continue

        if block_depth > 0:
            if line.startswith("/*", pos):
                block_depth += 1
                pos += 2
            elif line.startswith("*/", pos):
                block_depth -= 1
                pos += 2
            else:
                pos += 1
            continue

        rest = line[pos:]
        if rest.startswith("//"):
            i += 1
            pos = 0
            continue
        if rest.startswith("/*"):
            block_depth = 1
            pos += 2
            continue
        if rest.startswith('"""'):
            start_line = i
            buf: list[str] = []
            pos += 3
            while i < len(lines):
                line = lines[i]
                end = line.find('"""', pos)
                if end == -1:
                    buf.append(line[pos:])
                    i += 1
                    pos = 0
                    continue
                buf.append(line[pos:end])
                pos = end + 3
                break
            literals.append(Literal(start_line + 1, "\n".join(buf), lines[start_line]))
            continue
        if rest.startswith('"'):
            pos += 1
            buf = []
            while pos < len(line):
                ch = line[pos]
                if ch == "\\":
                    buf.append(line[pos:pos + 2])
                    pos += 2
                    continue
                if ch == '"':
                    pos += 1
                    break
                buf.append(ch)
                pos += 1
            literals.append(Literal(i + 1, "".join(buf), line))
            continue
        if rest.startswith("'"):
            # Littéral de caractère : 'a', '\n', '\u00e9'. Jamais une phrase, on saute jusqu'au
            # guillemet simple fermant.
            pos += 1
            while pos < len(line):
                if line[pos] == "\\":
                    pos += 2
                    continue
                if line[pos] == "'":
                    pos += 1
                    break
                pos += 1
            continue
        pos += 1

    return literals


def log_call_lines(source: str) -> set[int]:
    """Numéros de ligne (1-based) couverts par un appel `Log.x(...)`, appel multi-ligne compris.

    Les messages de journalisation ne sont pas de l'IHM : ils restent en français, ils s'adressent à
    qui lit un logcat. Sans ce filtre, ils représentaient à eux seuls la moitié du bruit du script.
    """
    covered: set[int] = set()
    depth = 0
    for number, line in enumerate(source.split("\n"), start=1):
        if depth > 0:
            covered.add(number)
        match = LOG_CALL_RE.search(line)
        if match and depth == 0:
            covered.add(number)
            depth = 1
            # Compte les parenthèses à partir de celle ouverte par l'appel.
            for ch in line[match.end():]:
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        break
            continue
        if depth > 0:
            for ch in line:
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        break
    return covered


def looks_french(text: str) -> bool:
    return bool(ACCENTED_RE.search(text) or FRENCH_WORD_RE.search(text))


def suspects(path: Path) -> list[Literal]:
    source = path.read_text(encoding="utf-8")
    lines = source.split("\n")
    logged = log_call_lines(source)
    found = []
    for literal in extract_literals(source):
        if literal.line in logged:
            continue
        if not looks_french(literal.text):
            continue
        if SQL_RE.search(literal.text):
            continue
        if is_marked(lines, literal.line):
            continue
        found.append(literal)
    return found


def is_marked(lines: list[str], line_number: int) -> bool:
    """Le littéral de la ligne [line_number] (1-based) porte-t-il un marqueur `i18n-ok` ?

    Le marqueur vaut sur sa propre ligne, et remonte à travers les lignes qui précèdent tant
    qu'elles sont des commentaires (le bloc explicatif posé au-dessus) ou qu'elles se terminent par
    un `+` (concaténation Kotlin étalée sur plusieurs lignes : les morceaux d'un même message se
    marquent une fois, pas un par un).
    """
    index = line_number - 1
    while index >= 0:
        line = lines[index]
        if IGNORE_MARKER in line:
            return True
        if index == line_number - 1:
            index -= 1
            continue
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.endswith("+"):
            index -= 1
            continue
        return False
    return False


def collect_files(extra_allow: list[str]) -> tuple[list[Path], set[str]]:
    allowed = set(TEMPORARY_ALLOWLIST) | set(extra_allow)
    files: list[Path] = []
    for root in SCAN_ROOTS:
        directory = REPO_ROOT / root
        if directory.is_dir():
            files.extend(sorted(directory.rglob("*.kt")))
    for name in SCAN_FILES:
        candidate = REPO_ROOT / name
        if candidate.is_file():
            files.append(candidate)
    return sorted(set(files)), allowed


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--allow",
        action="append",
        default=[],
        metavar="CHEMIN",
        help="Chemin relatif au dépôt d'un fichier encore en chantier : il est listé mais ne fait "
             "pas échouer le contrôle. Cumulable, s'ajoute à la liste temporaire du script.",
    )
    parser.add_argument("--no-fail", action="store_true", help="Sort toujours en 0 (relevé seul).")
    parser.add_argument("--quiet", action="store_true", help="Totaux seuls, sans le détail ligne à ligne.")
    args = parser.parse_args(argv[1:])

    files, allowed = collect_files(args.allow)

    blocking = 0
    tolerated = 0
    blocking_files: list[tuple[str, list[Literal]]] = []
    tolerated_files: list[tuple[str, list[Literal]]] = []

    for path in files:
        found = suspects(path)
        if not found:
            continue
        relative = str(path.relative_to(REPO_ROOT))
        if relative in allowed:
            tolerated += len(found)
            tolerated_files.append((relative, found))
        else:
            blocking += len(found)
            blocking_files.append((relative, found))

    def report(title: str, entries: list[tuple[str, list[Literal]]]) -> None:
        if not entries:
            return
        print(f"== {title}")
        for relative, found in entries:
            print(f"{relative} : {len(found)}")
            if not args.quiet:
                for literal in found:
                    print(f"    {literal.line}: {literal.source_line.strip()}")

    report("À migrer", blocking_files)
    report("Toléré (lot 3 en cours, exclusion temporaire)", tolerated_files)

    print("---")
    print(f"Total à migrer : {blocking} littéral(aux) suspect(s).")
    print(f"Total toléré   : {tolerated} littéral(aux) dans {len(tolerated_files)} fichier(s) exclus.")

    if blocking and not args.no_fail:
        print("Échec : un littéral français en dur subsiste hors de la liste d'exclusion (RIC-24).")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
