# Règles de conservation R8 (RIC-219). Minimales : une règle par chargement dynamique établi.
# Les règles par défaut d'Android (proguard-android-optimize.txt) s'appliquent déjà en plus.

# 1. jpx : XMLProvider.loadInstance() appelle java.util.ServiceLoader.load(XMLProvider.class)
#    (io.jenetics.jpx.XMLProvider, jpx 3.2.1). Le jar ne déclare aucun
#    META-INF/services/io.jenetics.jpx.XMLProvider (vérifié le 25/10/2026) : le repli
#    DefaultXMLProvider est instancié directement, mais un fournisseur déclaré plus tard par une
#    dépendance serait chargé par nom, donc le point d'entrée et ses sous-classes sont gardés.
-keep class io.jenetics.jpx.XMLProvider { *; }
-keep class * extends io.jenetics.jpx.XMLProvider { *; }

# 2. aalto-xml : jpx appelle XMLInputFactory.newInstance() et XMLOutputFactory.newInstance()
#    (XMLProvider, constructeur par défaut), qui instancient par nom l'implémentation citée dans
#    META-INF/services/javax.xml.stream.XMLInputFactory, XMLOutputFactory et XMLEventFactory du jar
#    aalto-xml 1.3.3. Sans ces règles, le nom est obscurci ou la classe supprimée et l'import GPX casse.
-keep class com.fasterxml.aalto.stax.InputFactoryImpl { *; }
-keep class com.fasterxml.aalto.stax.OutputFactoryImpl { *; }
-keep class com.fasterxml.aalto.stax.EventFactoryImpl { *; }

# 3. PROPOSITION HORS LISTE, à valider par le pilotage (RIC-219, arrêt obligatoire du kickoff) :
#    jpx 3.2.1, XML.copy(XMLStreamReader, Document), référence javax.xml.transform.stax.StAXSource,
#    absente d'Android (ni android.jar ni aalto-xml). R8 refuse sinon de terminer (classe manquante).
#    Règle générée par AGP (missing_rules.txt). Ne change pas le comportement : sans R8 la classe
#    manquait déjà à l'exécution, le chemin n'est de toute façon pas utilisable sur Android.
-dontwarn javax.xml.transform.stax.StAXSource
