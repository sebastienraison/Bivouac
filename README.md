# Bivouac

Application Android open source pour la préparation de randonnées itinérantes avec bivouac.

Importe une trace GPX multi-jours, affiche-la sur une carte OSM, positionne tes points de
bivouac directement sur le tracé, et récupère un tableau des segments journaliers (distance,
dénivelé, durée estimée) qui se met à jour automatiquement.

![Trace chargée, fond de carte Standard](screenshots/01_trace_standard.png)
![Trace chargée avec bivouac, fond de carte Randonnée](screenshots/02_trace_rando.png)
![Courbe de dénivelé et tableau des segments journaliers](screenshots/03_segments_table.png)

## Fonctionnalités (V1.1)

- Import d'une trace GPX via le sélecteur de fichiers système, ou directement depuis une autre
  application
- Affichage sur fond de carte OSM (osmdroid) avec sélecteur (standard, randonnée, satellite),
  pictos de départ/arrivée/boucle, recentrage sur la trace
- Ajout, déplacement (aimanté à la trace) et suppression des points de bivouac ; altitude et lien
  météo pour chaque point
- Courbe de dénivelé avec repères d'altitude/distance et position des bivouacs
- Tableau des segments journaliers : distance, durée estimée, D+, D-
- Export d'un segment au format GPX vers une autre application

Détail complet des fonctionnalités et limitations connues : [RELEASE_NOTES.md](RELEASE_NOTES.md).

## Stack technique

- Kotlin + Jetpack Compose (Material3)
- [osmdroid](https://github.com/osmdroid/osmdroid) pour la cartographie OSM
- [JPX](https://github.com/jenetics/jpx) pour la lecture des traces GPX
- Architecture MVVM (StateFlow)

## Compiler depuis les sources

```bash
./gradlew assembleDebug
```

Nécessite un SDK Android (API 34) et un JDK 17.

## Licence

Ce projet est distribué sous licence [GPLv3](LICENSE).

## Pourquoi open source ?

Ce projet a démarré comme un outil perso pour ne plus perdre mes bivouacs sur un coin de carte, et
il a pris de l'ampleur sans prévenir. Ne vous attendez pas à du code exemplaire, mais ça tourne —
et si ça peut servir à quelqu'un d'autre, tant mieux. Indulgence et retours bienvenus.

## Statut

V1.1 fonctionnelle. Développement actif.

## Développement

Code écrit avec l'assistance d'un modèle d'IA.
