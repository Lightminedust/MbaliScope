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
    mainClass = "org.mbali.MbaliScopeLauncher"
    applicationName = "MbaliScope"
}

// Captures du vrai Canvas pour vérifier cadrage, densité et détail sans lancer un scan réseau.
tasks.register<JavaExec>("renderProcessPreview") {
    group = "verification"
    description = "Génère les aperçus de la carte des processus dans app/build/previews."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.mbali.view.SpacetimePreview"
    if (providers.gradleProperty("livePreview").isPresent) args("live")
}

// La même vérification pour la carte du réseau, sur un réseau inventé : aucun balayage.
tasks.register<JavaExec>("renderNetworkPreview") {
    group = "verification"
    description = "Génère les aperçus de la carte du réseau dans app/build/previews."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.mbali.view.ConstellationPreview"
}

// org.openjfx.javafxplugin 0.1.0 (dernière version publiée, 2023) lit le projet
// pendant l'exécution de `run`, ce que le configuration cache de Gradle 9 refuse.
// On exclut seulement cette tâche du cache plutôt que de le désactiver partout.
tasks.named<JavaExec>("run") {
    notCompatibleWithConfigurationCache("org.openjfx.javafxplugin 0.1.0 accède au projet à l'exécution")
}

val windowsPackageDirectory = layout.buildDirectory.dir("jpackage")
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}.get()
val jpackageExecutable = javaLauncher.executablePath.asFile.parentFile.resolve(
    if (isWindows) "jpackage.exe" else "jpackage"
)
val windowsPackageInput = layout.buildDirectory.dir("install/MbaliScope/lib").get().asFile
val windowsPackageIcon = file("src/main/resources/org/mbali/assets/mbaliscope-icon.ico")
val packagedMainJar = tasks.named<Jar>("jar").get().archiveFileName.get()

val cleanWindowsPackage by tasks.registering(Delete::class) {
    delete(windowsPackageDirectory)
}

/**
 * Produit app/build/jpackage/MbaliScope/MbaliScope.exe avec l'icône de marque.
 * L'exécution depuis Gradle garde l'icône du Stage ; cet exécutable remplace aussi
 * l'icône du processus Java dans l'Explorateur et la barre des tâches Windows.
 */
tasks.register<Exec>("packageWindows") {
    group = "distribution"
    description = "Construit une image d'application Windows avec l'icône MbaliScope."
    dependsOn("installDist", cleanWindowsPackage)
    enabled = isWindows

    commandLine(
        jpackageExecutable.absolutePath,
        "--type", "app-image",
        "--name", "MbaliScope",
        "--app-version", "1.0.0",
        "--vendor", "Lightminedust",
        "--description", "Cartographie vivante du réseau local",
        "--input", windowsPackageInput.absolutePath,
        "--dest", windowsPackageDirectory.get().asFile.absolutePath,
        "--main-jar", packagedMainJar,
        "--main-class", "org.mbali.MbaliScopeLauncher",
        "--icon", windowsPackageIcon.absolutePath,
        "--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,jdk.management,jdk.unsupported",
        "--java-options", "-Dfile.encoding=UTF-8"
    )
}
