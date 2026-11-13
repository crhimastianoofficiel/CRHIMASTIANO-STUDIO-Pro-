# CRHIMASTIANO STUDIO PRO

Socle initial d'une application de studio musical Android native.

## Module Piano (prototype)
- Clavier tactile de trois octaves, de C3 à B5, avec touches blanches et noires.
- Défilement horizontal pour accéder à toute l'étendue, touches dessinées en Canvas.
- Événements Note On / Note Off multi-touch et jusqu'à 16 voix simultanées.
- Commande de volume maître et pédale Sustain.
- Synthèse sinusoïdale locale via Oboe; aucune connexion réseau n'est requise après compilation et installation.

**Important :** le clavier et les contrôles sont implémentés, mais le timbre reste un oscillateur de test, pas un piano échantillonné. Ce prototype ne doit pas être présenté comme un instrument de piano réaliste.

## Ouvrir et compiler
1. Ouvrir le dossier racine dans Android Studio avec JDK 17.
2. Laisser Gradle télécharger les dépendances, dont Google Oboe 1.9.3.
3. Lancer la configuration `app` sur un appareil Android ou un émulateur.

La compilation initiale nécessite Internet. Aucun wrapper Gradle n'est actuellement inclus. La cible définie est Android API 26 minimum et API 35 compile/target. Le TECNO Camon 40 est l'appareil visé, mais ce dépôt n'a pas encore été validé sur cet appareil.

Les répertoires générés `.gradle/`, `build/` et `.cxx/` ainsi que `local.properties` ne doivent pas être téléversés.

## Technologies
- Kotlin et Android SDK
- Android Gradle Plugin 8.7.3 / Kotlin 2.0.21
- C++17, CMake 3.22.1 et Google Oboe

## Licence
À définir par le propriétaire du projet.
