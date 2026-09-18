#!/usr/bin/env python3
"""RIC-187 : tests du generateur de ressources i18n (tools/i18n/generate_strings.py).

Pas un test JVM (le generateur est du Python pur, sans dependance Android) : a lancer avec

    python3 -m unittest tools/i18n/test_generate_strings.py

ou simplement

    python3 tools/i18n/test_generate_strings.py

Voir tools/i18n/README.md.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import generate_strings as g

SAMPLE_CSV = Path(__file__).parent / "testdata" / "sample-inventaire.csv"

HEADER = (
    '"priorité";"contexte";"fichier";"type";"clé";"fr actuel";"fr neutre proposé";'
    '"en proposé";"notes pilotage";"fr validé (Seb)";"en validé (Seb)";"commentaire Seb"\n'
)


def write_csv(rows: list[str]) -> Path:
    tmp = tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False, encoding="utf-8")
    tmp.write(HEADER)
    for row in rows:
        tmp.write(row + "\n")
    tmp.close()
    return Path(tmp.name)


class EscapingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.en_xml, self.fr_xml = g.generate(SAMPLE_CSV)

    def test_apostrophe_echappee(self) -> None:
        self.assertIn(
            '<string name="sample_apostrophe">Poignée du tiroir</string>', self.fr_xml
        )

    def test_pourcentage_double_quand_parametre(self) -> None:
        self.assertIn(
            '<string name="sample_percent_with_param">%1$s (100%% fait)</string>', self.fr_xml
        )
        self.assertIn(
            '<string name="sample_percent_with_param">%1$s (100%% complete)</string>',
            self.en_xml,
        )

    def test_pourcentage_simple_non_double_sans_parametre(self) -> None:
        self.assertIn('<string name="sample_percent_no_param">50% du temps</string>', self.fr_xml)

    def test_pluriel_devient_plurals(self) -> None:
        self.assertIn('<plurals name="sample_plural_count">', self.fr_xml)
        self.assertIn('<item quantity="one">%1$d randonnée</item>', self.fr_xml)
        self.assertIn('<item quantity="other">%1$d randonnées</item>', self.fr_xml)
        self.assertIn('<item quantity="one">%1$d hike</item>', self.en_xml)
        self.assertIn('<item quantity="other">%1$d hikes</item>', self.en_xml)

    def test_forme_many_ajoutee_au_francais_seulement(self) -> None:
        # RIC-191 : Lint (MissingQuantity) exige "many" en francais, que CLDR reserve aux multiples
        # d'un million. Identique a "other", et jamais ajoutee a l'anglais, qui ne la connait pas.
        self.assertIn('<item quantity="many">%1$d randonnées</item>', self.fr_xml)
        self.assertNotIn('quantity="many"', self.en_xml)
        # L'ajout se fait au rendu, pas dans l'inventaire : celui-ci garde les memes quantites dans
        # les deux langues, ce que build_entry() continue d'exiger.

    def test_valeur_validee_prevaut_sur_proposee(self) -> None:
        self.assertIn(
            '<string name="sample_validated_override">Texte validé par Seb</string>', self.fr_xml
        )
        self.assertIn(
            '<string name="sample_validated_override">Validated by Seb</string>', self.en_xml
        )
        self.assertNotIn("Neutre non retenu", self.fr_xml)
        self.assertNotIn("Neutral not kept", self.en_xml)

    def test_caracteres_speciaux_xml(self) -> None:
        self.assertIn(
            '<string name="sample_special_chars">Titre « spécial » &amp; &lt;balise&gt;</string>',
            self.fr_xml,
        )
        self.assertIn(
            '<string name="sample_special_chars">Special \\"title\\" &amp; &lt;tag&gt;</string>',
            self.en_xml,
        )

    def test_idempotent(self) -> None:
        en2, fr2 = g.generate(SAMPLE_CSV)
        self.assertEqual(self.en_xml, en2)
        self.assertEqual(self.fr_xml, fr2)

    def test_contexte_commente(self) -> None:
        self.assertIn("<!-- Contexte de test -->", self.fr_xml)
        self.assertIn("<!-- Autre contexte -->", self.fr_xml)

    def test_app_name_fixe(self) -> None:
        self.assertIn('<string name="app_name">Bivouac</string>', self.fr_xml)
        self.assertIn('<string name="app_name">Bivouac</string>', self.en_xml)


class ErrorPathTest(unittest.TestCase):
    def test_cle_dupliquee_leve_une_erreur(self) -> None:
        csv_path = write_csv(
            [
                '"1";"Ctx";"F.kt:1";"texte";"dup_key";"Un";"Un";"One";"";"";"";""',
                '"1";"Ctx";"F.kt:2";"texte";"dup_key";"Deux";"Deux";"Two";"";"";"";""',
            ]
        )
        with self.assertRaises(g.GeneratorError) as ctx:
            g.generate(csv_path)
        self.assertIn("dupliquée", str(ctx.exception))

    def test_parametres_incoherents_leve_une_erreur(self) -> None:
        csv_path = write_csv(
            [
                '"1";"Ctx";"F.kt:1";"format";"bad_params";"Valeur %1$s";"Valeur %1$s";'
                '"Value %1$d";"";"";"";""',
            ]
        )
        with self.assertRaises(g.GeneratorError) as ctx:
            g.generate(csv_path)
        self.assertIn("parametres", str(ctx.exception).lower())

    def test_forme_plurielle_other_manquante_leve_une_erreur(self) -> None:
        csv_path = write_csv(
            [
                '"1";"Ctx";"F.kt:1";"pluriel";"bad_plural";"one: seul";"one: seul";'
                '"one: only";"";"";"";""',
            ]
        )
        with self.assertRaises(g.GeneratorError) as ctx:
            g.generate(csv_path)
        self.assertIn("other", str(ctx.exception).lower())

    def test_pluriel_sans_balise_se_replie_en_string(self) -> None:
        csv_path = write_csv(
            [
                '"1";"Ctx";"F.kt:1";"pluriel";"fragment_key";"fragment fr";"fragment fr";'
                '"fragment en";"";"";"";""',
            ]
        )
        en_xml, fr_xml = g.generate(csv_path)
        self.assertIn('<string name="fragment_key">fragment fr</string>', fr_xml)
        self.assertNotIn("<plurals", fr_xml)


if __name__ == "__main__":
    unittest.main()
