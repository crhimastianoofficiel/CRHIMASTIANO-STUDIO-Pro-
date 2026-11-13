# CRHIMASTIANO STUDIO PRO

Socle initial d'une application de studio musical Android native.

## Contenu
- Application Android native en Kotlin
- Interface tactile sombre avec clavier de notes
- Moteur audio C++ avec Google Oboe et plusieurs voix simultanées
- Génération locale d'un son de test (oscillateur)

**État : prototype de fondation.** Les banques d'instruments, le piano roll, le séquenceur, l'enregistrement multipiste et l'export audio restent à développer.

## Structure du projet

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── native_audio.cpp
│       └── java/com/crhimastiano/studiopro/
│           └── MainActivity.kt
├── gradle.properties
└── settings.gradle.kts
```

## Ouvrir et compiler
1. Cloner ou télécharger ce dépôt, puis ouvrir son dossier racine dans Android Studio.
2. Utiliser JDK 17. Les versions déclarées sont Android Gradle Plugin 8.7.3 et Kotlin 2.0.21.
3. Laisser Android Studio synchroniser Gradle et télécharger les dépendances, dont Oboe 1.9.3.
4. Connecter un téléphone Android avec le débogage USB ou lancer un émulateur, puis exécuter la configuration `app`.

La première synchronisation nécessite une connexion Internet pour récupérer les dépendances. Une fois l'application compilée et installée, le moteur de son de test ne nécessite pas de connexion réseau. Aucun wrapper Gradle n'est actuellement inclus dans ce dépôt.

Ne téléversez pas les dossiers générés `.gradle/`, `build/`, `.cxx/` ou `local.properties`.

## Compatibilité ciblée
- Android minimum : API 26 (Android 8.0)
- Compile/target SDK : 35
- Audio : Oboe, faible latence demandée; fréquence d'échantillonnage native choisie par l'appareil
- Appareil visé : TECNO Camon 40; une validation sur appareil réel reste nécessaire

## Dépôt
https://github.com/crhimastianoofficiel/CRHIMASTIANO-STUDIO-Pro-

## Technologies
- Kotlin et Android Gradle Plugin
- C++17 et CMake
- Google Oboe
- Android SDK

## Licence
À définir par le propriétaire du projet.
