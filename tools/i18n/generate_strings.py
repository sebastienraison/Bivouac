#!/usr/bin/env python3
"""RIC-187 (lot 0 du chantier i18n RIC-24) : generation des ressources de chaines.

Lit l'inventaire CSV (source de verite des textes, voir docs/pilotage/i18n/) et ecrit les deux
fichiers de ressources Android :
  - app/src/main/res/values/strings.xml       (anglais, langue par defaut)
  - app/src/main/res/values-fr/strings.xml    (francais)

Usage :
    python3 tools/i18n/generate_strings.py chemin/vers/inventaire.csv

Le CSV attendu est separe par ";", guillemets doubles, encodage UTF-8, avec l'entete suivant
(dans cet ordre) :
    priorite ; contexte ; fichier ; type ; cle ; fr actuel ; fr neutre propose ; en propose ;
    notes pilotage ; fr valide (Seb) ; en valide (Seb) ; commentaire Seb

Regle de priorite : si "fr valide" (resp. "en valide") est non vide, elle l'emporte sur
"fr neutre propose" (resp. "en propose").

Le type "pluriel" porte ses formes sous la forme "one: ... | other: ..." (les quantites CLDR
zero/one/two/few/many/other sont toutes acceptees) et devient une ressource <plurals>. Le type
"format" est une chaine a parametres (%1$s, %1$d...) et reste une simple <string>, comme tous les
autres types.

Le script est idempotent : deux executions sur le meme CSV produisent des fichiers identiques
(l'ordre des ressources suit l'ordre des lignes du CSV).
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

# Colonnes attendues, dans l'ordre exact du CSV (voir docs/pilotage/i18n/strings-inventaire-v4.csv).
COL_PRIORITE = 0
COL_CONTEXTE = 1
COL_FICHIER = 2
COL_TYPE = 3
COL_CLE = 4
COL_FR_ACTUEL = 5
COL_FR_NEUTRE = 6
COL_EN_PROPOSE = 7
COL_NOTES = 8
COL_FR_VALIDE = 9
COL_EN_VALIDE = 10
COL_COMMENTAIRE = 11
NB_COLONNES = 12

# Quantites CLDR reconnues pour le type "pluriel", dans l'ordre ou Android les attend a l'affichage
# (l'ordre d'ecriture n'a pas d'incidence fonctionnelle, seulement la lisibilite du fichier genere).
PLURAL_QUANTITIES = ("zero", "one", "two", "few", "many", "other")

# clé -> "Bivouac" dans les deux langues. Pas dans l'inventaire (c'est une ressource preexistante,
# posee avant le chantier i18n) : le generateur la fixe lui-meme plutot que de l'exiger du CSV.
FIXED_ENTRIES = [
    ("app_name", "Bivouac", "Bivouac"),
]

# Cle a NE PAS generer : bilan_month_initials_array porte les 12 initiales de mois de l'axe du
# graphique de progression du Bilan (J F M A M J J A S O N D). Note pilotage explicite (relecture
# RIC-187) : pas de ressource <string-array>, Month.getDisplayName(TextStyle.NARROW, locale) donne
# directement l'initiale localisee (java.time, disponible des minSdk 26). Tranche au lot 0, geree
# cote Kotlin dans bilan/BilanFormatting.kt (monthInitial), jamais generee ici.
SKIPPED_KEYS = {"bilan_month_initials_array"}


class GeneratorError(Exception):
    """Erreur bloquante de generation : cle dupliquee, parametres incoherents, pluriel casse."""


class StringEntry:
    def __init__(self, key: str, context: str, kind: str, fr: str, en: str):
        self.key = key
        self.context = context
        self.kind = kind  # "string" ou "plurals"
        self.fr = fr
        self.en = en


def read_csv_rows(csv_path: Path) -> list[list[str]]:
    with csv_path.open(newline="", encoding="utf-8") as f:
        reader = csv.reader(f, delimiter=";", quotechar='"')
        rows = list(reader)
    if not rows:
        raise GeneratorError(f"CSV vide : {csv_path}")
    header, data = rows[0], rows[1:]
    if len(header) != NB_COLONNES:
        raise GeneratorError(
            f"En-tete inattendu ({len(header)} colonnes, {NB_COLONNES} attendues) : {header}"
        )
    for i, row in enumerate(data, start=2):
        if len(row) != NB_COLONNES:
            raise GeneratorError(f"Ligne {i} : {len(row)} colonnes, {NB_COLONNES} attendues.")
    return data


def resolve_value(row: list[str], proposed_col: int, validated_col: int) -> str:
    validated = row[validated_col].strip()
    return validated if validated else row[proposed_col]


import re

PARAM_PATTERN = re.compile(r"%(\d+)\$([sd])")
PLURAL_TAG_PATTERN = re.compile(
    r"\b(" + "|".join(PLURAL_QUANTITIES) + r")\s*:\s*(.*?)(?=(?:\s*\|\s*(?:"
    + "|".join(PLURAL_QUANTITIES)
    + r")\s*:)|$)",
    re.DOTALL,
)


def extract_params(text: str) -> frozenset[str]:
    return frozenset(f"%{n}${t}" for n, t in PARAM_PATTERN.findall(text))


def parse_plural_forms(text: str) -> dict[str, str]:
    """Coupe "one: X | other: Y" (quantites CLDR quelconques) en {quantite: texte}.

    Renvoie un dict vide si le texte ne porte aucune balise de quantite reconnue (cas des lignes
    "pluriel" de l'inventaire qui sont en realite de simples fragments de texte, pas encore
    recomposes en vrai plurals Android -- voir generate() pour le repli sur <string> simple).
    """
    matches = PLURAL_TAG_PATTERN.findall(text)
    return {quantity: value.strip() for quantity, value in matches}


def build_entry(row: list[str]) -> StringEntry | None:
    context = row[COL_CONTEXTE]
    kind_csv = row[COL_TYPE]
    key = row[COL_CLE]

    if key in SKIPPED_KEYS:
        return None

    fr_raw = resolve_value(row, COL_FR_NEUTRE, COL_FR_VALIDE)
    en_raw = resolve_value(row, COL_EN_PROPOSE, COL_EN_VALIDE)

    if not fr_raw.strip() or not en_raw.strip():
        raise GeneratorError(f"Cle '{key}' : valeur fr ou en vide.")

    fr_params = extract_params(fr_raw)
    en_params = extract_params(en_raw)
    if fr_params != en_params:
        raise GeneratorError(
            f"Cle '{key}' : jeu de parametres different entre fr {sorted(fr_params)} "
            f"et en {sorted(en_params)}."
        )

    if kind_csv == "pluriel":
        fr_forms = parse_plural_forms(fr_raw)
        en_forms = parse_plural_forms(en_raw)
        if not fr_forms and not en_forms:
            # Fragment de texte simple, pas encore un vrai plurals Android (ex. les paires
            # storage_recompress_offer_count_singular/_plural, ou journal_error_photo_save_failed_*) :
            # le code de production actuel les consomme comme deux chaines plates independantes,
            # la vraie ressource <plurals> viendra avec la migration d'ecran (lots 1 a 4). Genere
            # ici comme <string> simple plutot que de bloquer toute la generation.
            print(
                f"[generate_strings] avertissement : cle '{key}' typee 'pluriel' sans balise "
                "one/other reconnue, generee comme <string> simple (fragment pre-migration).",
                file=sys.stderr,
            )
            return StringEntry(key, context, "string", fr_raw, en_raw)
        if not fr_forms or not en_forms:
            raise GeneratorError(f"Cle '{key}' : forme plurielle manquante dans fr ou en.")
        if "other" not in fr_forms or "other" not in en_forms:
            raise GeneratorError(f"Cle '{key}' : forme plurielle 'other' manquante (obligatoire).")
        if set(fr_forms) != set(en_forms):
            raise GeneratorError(
                f"Cle '{key}' : quantites plurielles differentes entre fr {sorted(fr_forms)} "
                f"et en {sorted(en_forms)}."
            )
        return StringEntry(key, context, "plurals", fr_forms, en_forms)  # type: ignore[arg-type]

    return StringEntry(key, context, "string", fr_raw, en_raw)


def escape_android(text: str, has_params: bool) -> str:
    # Ordre important : backslash puis entites XML puis guillemets/apostrophe puis nouvelle ligne,
    # et enfin le doublement du % (qui doit ignorer les %n$s/%n$d deja valides).
    out = text.replace("\\", "\\\\")
    out = out.replace("&", "&amp;")
    out = out.replace("<", "&lt;").replace(">", "&gt;")
    out = out.replace('"', '\\"')
    out = out.replace("'", "\\'")
    out = out.replace("\r\n", "\n").replace("\n", "\\n")
    if has_params:
        # Protege les specificateurs deja valides (%1$s, %2$d...) avant de doubler tout le reste.
        protected = PARAM_PATTERN.sub(lambda m: f"\x00{m.group(1)}\x01{m.group(2)}\x02", out)
        protected = protected.replace("%", "%%")
        out = re.sub("\x00(\\d+)\x01([sd])\x02", r"%\1$\2", protected)
    return out


def render_string(key: str, value: str) -> str:
    has_params = bool(PARAM_PATTERN.search(value))
    escaped = escape_android(value, has_params)
    return f'    <string name="{key}">{escaped}</string>'


def render_plurals(key: str, forms: dict[str, str]) -> str:
    has_params = any(PARAM_PATTERN.search(v) for v in forms.values())
    lines = [f'    <plurals name="{key}">']
    for quantity in PLURAL_QUANTITIES:
        if quantity in forms:
            escaped = escape_android(forms[quantity], has_params)
            lines.append(f'        <item quantity="{quantity}">{escaped}</item>')
    lines.append("    </plurals>")
    return "\n".join(lines)


HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
    FICHIER GENERE, NE PAS EDITER A LA MAIN.

    Source : docs/pilotage/i18n/strings-inventaire-v4.csv (inventaire de l'agent de pilotage,
    RIC-24/RIC-187). Toute correction se fait dans l'inventaire, puis :

        python3 tools/i18n/generate_strings.py docs/pilotage/i18n/strings-inventaire-v4.csv

    Voir tools/i18n/README.md.
-->
<resources>
"""

FOOTER = "</resources>\n"


def render_resources(entries: list[StringEntry], lang: str) -> str:
    parts = [HEADER]
    parts.append("    <!-- Nom de l'application (hors inventaire, ressource fixe) -->\n")
    for key, fr, en in FIXED_ENTRIES:
        value = fr if lang == "fr" else en
        parts.append(render_string(key, value) + "\n")

    current_context = None
    for entry in entries:
        if entry.context != current_context:
            current_context = entry.context
            parts.append(f"\n    <!-- {current_context} -->\n")
        value = entry.fr if lang == "fr" else entry.en
        if entry.kind == "plurals":
            parts.append(render_plurals(entry.key, value) + "\n")  # type: ignore[arg-type]
        else:
            parts.append(render_string(entry.key, value) + "\n")  # type: ignore[arg-type]

    parts.append(FOOTER)
    return "".join(parts)


def generate(csv_path: Path) -> tuple[str, str]:
    rows = read_csv_rows(csv_path)
    entries: list[StringEntry] = []
    seen_keys: set[str] = set()
    errors: list[str] = []

    for i, row in enumerate(rows, start=2):
        key = row[COL_CLE]
        if key in seen_keys and key not in SKIPPED_KEYS:
            errors.append(f"Ligne {i} : cle dupliquée '{key}'.")
            continue
        seen_keys.add(key)
        try:
            entry = build_entry(row)
        except GeneratorError as exc:
            errors.append(f"Ligne {i} : {exc}")
            continue
        if entry is not None:
            entries.append(entry)

    if errors:
        raise GeneratorError("\n".join(errors))

    return render_resources(entries, "en"), render_resources(entries, "fr")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"Usage : {argv[0]} chemin/vers/inventaire.csv", file=sys.stderr)
        return 2

    csv_path = Path(argv[1])
    if not csv_path.is_file():
        print(f"Introuvable : {csv_path}", file=sys.stderr)
        return 2

    repo_root = Path(__file__).resolve().parents[2]
    res_dir = repo_root / "app" / "src" / "main" / "res"

    try:
        en_xml, fr_xml = generate(csv_path)
    except GeneratorError as exc:
        print(f"[generate_strings] erreur :\n{exc}", file=sys.stderr)
        return 1

    en_path = res_dir / "values" / "strings.xml"
    fr_path = res_dir / "values-fr" / "strings.xml"
    fr_path.parent.mkdir(parents=True, exist_ok=True)
    en_path.write_text(en_xml, encoding="utf-8")
    fr_path.write_text(fr_xml, encoding="utf-8")
    print(f"[generate_strings] ecrit {en_path} et {fr_path}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
