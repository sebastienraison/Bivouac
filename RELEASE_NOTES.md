# Bivouac — Notes de version

## V2.2.1

**Bugfix critique :**

- Sur Android 8 à 13, l'import d'une trace GPX échouait systématiquement ("Trace incorrecte ou
  fichier illisible"), rendant l'app inutilisable sur ces versions — l'app ne fonctionnait
  en pratique que sur Android 14+. Corrigé (bibliothèque de lecture GPX s'appuyant sur une API
  Java absente des Android antérieurs, désormais fournie par l'app elle-même). Merci au testeur
  bénévole de la revue F-Droid qui a découvert et documenté le problème.

## V2.2.0

**Journal — photos (nouveau) :**

- Associer des photos de la galerie du téléphone à une randonnée du Journal : positionnées
  automatiquement sur la trace grâce à leurs données GPS, marqueurs sur la carte, carrousel à
  balayage dans la bulle d'un marqueur (avec l'heure de prise de vue), galerie de la sortie et
  visionneuse plein écran
- Sélecteur de photos intégré à l'app plutôt que le sélecteur système d'Android : ce dernier
  supprime la position GPS des photos qu'il transmet, ce qui rendrait leur placement sur la trace
  impossible. D'où deux nouvelles permissions médias (lecture des images, accès à leur
  localisation), demandées uniquement à la première utilisation de la fonctionnalité
- Fonctionnalité entièrement débrayable dans les Réglages ; purge de toutes les photos importées
  possible au même endroit
- Le mode édition d'une randonnée (note, tags, photos) est désormais transactionnel : rien n'est
  modifié tant qu'on n'enregistre pas, et quitter avec des modifications en attente demande
  explicitement quoi en faire (enregistrer, abandonner, ou rester)

**Sauvegarde / restauration :**

- Progression affichée pendant la sauvegarde, la restauration et la purge des photos (dialogue
  bloquant avec compteur) ; les opérations lourdes ne peuvent plus se chevaucher (sauvegarde,
  restauration, imports GPX, import/purge de photos)
- Détection des sauvegardes incomplètes : un fichier de sauvegarde tronqué (transfert interrompu,
  espace insuffisant) est refusé à la restauration au lieu de passer inaperçu

**Bugfixes :**

- Le profil d'altitude d'une trace restait entièrement vide dès qu'un seul point du GPX n'avait
  pas d'altitude — les trous sont désormais comblés par interpolation

## V2.1.0

**Bilan (nouveau) :**

- Nouvel onglet Bilan : vue d'ensemble du Journal — totaux cumulés, graphique de progression
  mensuelle (sorties, km, D+, vitesse, bivouacs) sur tout l'historique, et tes records personnels
  (km-effort, vitesse ascensionnelle, altitude max atteinte, bivouac le plus haut, plus gros trek...).
  Chaque record renvoie directement à la sortie concernée dans le Journal.

**Réglages :**

- Numéro de version et date de build affichés en bas de l'écran, pour identifier précisément quelle
  version tourne sur l'appareil

**Bugfixes :**

- Planification : au tout premier lancement de l'app, l'écran "Aucune trace en préparation" pouvait
  s'afficher brièvement même quand une session précédente était sur le point d'être restaurée
- Planification : après avoir tué puis relancé l'app sur une trace déjà enregistrée en banque, fermer
  l'écran redemandait à tort une confirmation de sauvegarde — et sauvegarder à cette invite dupliquait
  la trace au lieu de simplement fermer

## V2.0.2

**Réglages (nouveau) :**

- Provision de pause réglable dans l'estimation de durée (Vitesse personnalisée) : ajoute une marge
  de temps aux estimations pour tenir compte des pauses (photo, casse-croûte, arrêts...), réglable
  manuellement ou mesurée automatiquement sur le Journal/la sélection selon le mode choisi

**Bugfixes :**

- Le calcul automatique de pénalité D+ (Auto/Sélection) pouvait être surestimé quand un arrêt était
  pris en pleine montée — désormais exclu du calcul, comme c'était déjà le cas sur terrain plat
- Duplication d'une trace du Journal vers Planification : le dialogue de renommage pouvait se
  refermer tout seul avant d'avoir pu taper un nom
- Planification : un trek multi-jours dupliqué depuis le Journal, dont l'enregistrement s'était
  arrêté loin du bivouac un soir, pouvait afficher un trajet fictif sur la carte et gonfler la
  distance totale affichée — même correctif que celui déjà appliqué au Journal
- Une trace mono-jour sans aucun point de bivouac posé n'avait aucun moyen d'export GPX depuis
  Planification — ajouté au menu de la trace
- Ouvrir une trace de la banque devenue illisible affichait un écran d'erreur qui faisait
  disparaître le reste de la liste ; affiche désormais un message ponctuel, sans perturber le reste
- Journal : mise en page de la ligne de bivouac (police des heures, icône) alignée sur le reste de
  l'interface
- Renforce la protection contre un risque théorique de sauvegarde incomplète en cas d'accès
  concurrent à la base pendant l'opération

## V2.0.1

**Bugfixes :**

- Le calcul automatique de vitesse/pénalité D+ (Réglages, mode Auto ou Sélection) pouvait varier
  fortement selon les randonnées présentes dans le Journal ou la sélection, surtout avec peu de
  randonnées (une dizaine ou moins) — corrigé par un calcul plus robuste, à l'intérieur de chaque
  randonnée plutôt qu'en comparant les randonnées entre elles.

## V2.0

**Journal (nouveau) :**

- Importer une ou plusieurs traces GPX déjà réalisées ; plusieurs fichiers sélectionnés ensemble
  sont reconnus comme les jours d'une même sortie plutôt que des randonnées séparées
- Liste chronologique groupée par année, avec distance, durée et dénivelé cumulés
- Détail en lecture seule d'une trace : carte, profil altimétrique, bivouacs relevés automatiquement
  aux coupures entre les jours d'une sortie de plusieurs jours
- Note libre et tags sur chaque trace ; filtrage de la liste par tag
- Sélection multiple de traces pour les superposer sur la même carte
- Suppression d'une trace
- Dupliquer une trace du Journal vers Planification pour reprendre un itinéraire déjà parcouru

**Planification :**

- Même tiroir à trois crans (Synthèse / Profil / Détails) que le Journal, pour une interface
  cohérente entre les deux univers
- L'app se relance sur le dernier univers consulté (Journal ou Planification) ; un fichier GPX reçu
  d'une autre application demande explicitement dans lequel l'ouvrir quand ce n'est pas évident

**Cartographie :**

- Fond de carte satellite (Esri World Imagery), désactivable comme le lien météo depuis les
  nouveaux Réglages
- Flèches de direction sur le tracé, y compris pour les boucles

**Réglages (nouveau) :**

- Écran de réglages, accessible depuis le menu de section
- Vitesse personnalisée pour l'estimation de durée : manuelle (vitesse à plat et pénalité D+
  éditables), automatique (calculée à partir de tout le Journal, recalculée à chaque import) ou par
  sélection de traces représentatives
- Interrupteur pour désactiver les fonctionnalités non libres (fond satellite Esri, lien météo
  Meteoblue)
- Sauvegarde et restauration complètes de la base et des réglages (format ouvert), gestion des
  versions des backups et de l'app

**Divers :**
- La mise à jour depuis n'importe quelle version précédemment publiée de l'app préserve
  intégralement les traces, bivouacs et randonnées déjà enregistrés

**Bugfixes :**

- À l'ouverture d'une trace en Planification, le bas du tracé pouvait rester masqué par le tiroir
  tant qu'on n'appuyait pas sur recentrage
- Le contenu GPX des traces (banque de Planification et session en cours) est désormais stocké
  dans des fichiers plutôt qu'en base, ce qui élimine un risque de plantage à l'ouverture d'une
  trace très volumineuse ou très riche en points
- Ouvrir une trace pouvait échouer systématiquement juste après une sauvegarde, sans qu'un
  redémarrage de l'app ne soit nécessaire pour que ça reparte
- Le filtre par tag du Journal pouvait continuer de retenir un tag qui n'existait plus
- Le dialogue de choix d'univers (Journal ou Planification) pouvait se rouvrir après une rotation
  d'écran
- Fermer une trace reçue depuis une autre application sans jamais l'avoir enregistrée ne prévenait
  pas de la perte, contrairement à une trace déjà enregistrée puis modifiée
- Correction d'un crash possible sur la courbe de dénivelé dans un cas de mesure transitoire de
  hauteur nulle
- Suppression de bulles d'info parasites : au tap manqué près d'une trace, et au clic court sur un
  point de bivouac

## V1.3

**Banque de traces (nouveau) :**

- Enregistrer, renommer, dupliquer, supprimer et lister plusieurs traces planifiées
- Indicateur de modifications non enregistrées, confirmation avant de fermer une trace modifiée
  sans l'enregistrer

**Cartographie :**

- Zoom de départ de la carte adapté à la France quand l'appareil y est configuré

**Bugfixes :**

- L'import d'une trace GPX contenant des données de capteur (fréquence cardiaque, cadence...),
  fréquentes sur les exports de montres/GPS de randonnée, faisait échouer l'import
- En mode paysage, recentrer la carte sur la trace pouvait couper le haut du tracé

## V1.2

**Persistance (nouveau) :**

- La trace en cours et ses points de bivouac sont sauvegardés automatiquement et rouverts au
  lancement de l'app, sauf si un GPX arrive entre-temps depuis une autre application (qui reste
  prioritaire)
- Le fond de carte sélectionné (Standard, Randonnée, Satellite) est mémorisé entre les sessions

**Bugfixes :**

- Le bouton de recentrage bougeait légèrement à l'ouverture et à la fermeture du menu des fonds de
  carte

## V1.1

**Import et affichage :**

- Import d'une trace directement depuis une autre application (ouverture d'un fichier `.gpx` ou
  partage vers l'app), en plus du sélecteur système
- Sélecteur de fonds de carte (Standard, Randonnée OpenTopoMap, Satellite Esri World Imagery —
  Randonnée par défaut)
- Bouton de recentrage sur la trace, qui tient compte de la zone effectivement visible au-dessus
  du tiroir (pas centré sur tout l'écran s'il est en partie masqué)

**Points de bivouac :**

- Altitude du point affichée ; icône météo ouvrant les prévisions (meteoblue, coordonnées du
  point, langue FR/EN selon celle de l'appareil)

**Courbe de dénivelé (nouveau) :**

- Profil altimétrique de la trace complète, affiché dès qu'une trace est chargée
- Repères d'altitude et de distance, espacés régulièrement entre les bornes puis arrondis
- Position de chaque point de bivouac marquée sur la courbe, avec sa distance indiquée sur l'axe ;
  suit le glissement du point en temps réel, avant même de relâcher le geste

**Interface :**

- Hauteur repliée du tiroir ajustée automatiquement à son contenu (au lieu d'une valeur fixe)

**Bugfixes :**

- La trace et les points de bivouac étaient perdus lors d'un changement d'orientation de l'écran
- En mode paysage, le tiroir pouvait masquer l'essentiel de la carte
- Le tableau des segments n'était pas défilable une fois le tiroir déplié en plein écran, rendant
  les points de bivouac au-delà de la première page inatteignables
- Le picto du point de bivouac dans le tableau apparaissait déformé (bords rognés)

## V1

Première version fonctionnelle :

- Import d'une trace GPX
- Positionnement des points de bivouac sur la carte
- Tableau des segments journaliers

**Import et affichage :**

- Import d'un fichier GPX via le sélecteur système (multi-trace/multi-segment aplatis en une
  trace continue)
- Affichage sur fond de carte OSM (osmdroid), trace en pointillés bleus avec contour blanc pour
  ressortir sur tout type de fond
- Zoom automatique sur l'emprise de la trace à l'ouverture (marge de 5 % sur les bords)
- Pictos de départ (vert, "play") et d'arrivée (rouge, "stop") ; picto combiné départ/arrivée en
  cas de boucle

**Points de bivouac :**

- Ajout d'un point en tapant sur la trace (tolérance de 24dp)
- Déplacement par glisser, aimanté en temps réel au point de trace le plus proche
- Suppression depuis le tableau des segments

**Tableau des segments :**

- Généré automatiquement dès le premier point de bivouac posé
- Par segment : distance, durée estimée, D+, D-
- Mise à jour en temps réel pendant le glissement d'un point
- Total général affiché en tête du tiroir, estompé dès qu'il y a des segments (pour ne pas faire
  doublon visuellement)
- Export d'un segment au format GPX, ouverture directe dans une application tierce compatible

**Interface :**

- Tiroir bas extensible (glisser vers le haut) au-dessus d'une carte plein écran
- Icône d'application et pictos de bivouac assortis (tente orange)

**Détails techniques notables :**

- Durée estimée à partir d'une vitesse de base (3,5 km/h) corrigée du D+ selon une règle simplifiée
  proche de Naismith (100 m de D+ ≈ 1 km équivalent plat)

**Limitations connues :**

- Le D- global peut légèrement différer de la somme des segments (lissage d'altitude recalculé
  indépendamment par segment)
- Pas de sauvegarde/reprise d'une session (tout est perdu à la fermeture de l'app)
