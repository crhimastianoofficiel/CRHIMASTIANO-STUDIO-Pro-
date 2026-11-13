# CRHIMASTIANO STUDIO PRO

Socle initial d'une application de studio musical Android native.

## Contenu
- Application Android native en Kotlin
- Interface tactile sombre avec clavier de notes
- Moteur audio C++ avec Google Oboe
- Génération locale d'un son de test (oscillateur)

**État : prototype de fondation.** Les banques d'instruments, le piano roll, le séquenceur, l'enregistrement multipiste et l'export audio restent à développer.

## Structure du projet

```text
.
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── native_audio.cpp
│       └── java/com/crhimastiano/studiopro/
│           └── MainActivity.kt
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Ouvrir et compiler
1. Cloner ou télécharger ce dépôt, puis ouvrir son dossier racine dans Android Studio.
2. Utiliser JDK 17 et laisser Android Studio synchroniser Gradle et télécharger les dépendances (dont Oboe).
3. Connecter un téléphone Android avec le débogage USB ou lancer un émulateur, puis exécuter la configuration `app`.

La première synchronisation nécessite une connexion Internet. Ne téléversez pas les dossiers générés `.gradle/` ou `build/`, ni `local.properties`.

## Dépôt
https://github.com/crhimastianoofficiel/CRHIMASTIANO-STUDIO-Pro-

## Technologies
- Kotlin et Android Gradle Plugin
- C++17 et CMake
- Google Oboe
- Android SDK 35 (minimum Android 8.0 / API 26)

## Licence
À définir par le propriétaire du projet.
