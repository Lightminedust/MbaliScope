plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

repositories {
    mavenCentral()
}

dependencies {
    // Les artefacts JavaFX ne se déclarent pas ici : le plugin openjfx les ajoute
    // à partir du bloc javafx{} ci-dessous, avec le classifier de plateforme
    // (win/linux/mac) qui embarque les bibliothèques natives.
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("6.0.1")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls")
}

application {
    mainClass = "org.mbali.App"
}

// org.openjfx.javafxplugin 0.1.0 (dernière version publiée, 2023) lit le projet
// pendant l'exécution de `run`, ce que le configuration cache de Gradle 9 refuse.
// On exclut seulement cette tâche du cache plutôt que de le désactiver partout.
tasks.named<JavaExec>("run") {
    notCompatibleWithConfigurationCache("org.openjfx.javafxplugin 0.1.0 accède au projet à l'exécution")
}
