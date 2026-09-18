import java.net.URI
import java.time.Instant

plugins {
    java
}

val josmVersion = providers.gradleProperty("josm.version").get()
val josmMinVersion = providers.gradleProperty("josm.minVersion").get()
val requiredPlugins = providers.gradleProperty("josm.requiredPlugins").get()
    .split(";").map { it.trim() }.filter { it.isNotEmpty() }

group = "de.alkisselector"
version = providers.gradleProperty("plugin.version").get()

repositories {
    mavenCentral()
    maven("https://josm.openstreetmap.de/repository/releases/")
}

// ---------------------------------------------------------------------------
// Benötigte JOSM-Plugins (utilsplugin2, jts) werden nicht über Maven verteilt,
// sondern direkt aus dem JOSM-Plugin-Verzeichnis geladen.
// ---------------------------------------------------------------------------
val josmPluginDir = layout.buildDirectory.dir("josm-plugins")

val downloadJosmPlugins = tasks.register("downloadJosmPlugins") {
    description = "Lädt die benötigten JOSM-Plugins herunter."
    val names = requiredPlugins
    val outDir = josmPluginDir
    outputs.dir(outDir)
    doLast {
        names.forEach { name ->
            val target = outDir.get().file("$name.jar").asFile
            if (!target.exists()) {
                target.parentFile.mkdirs()
                val url = URI("https://josm.openstreetmap.de/osmsvn/applications/editors/josm/dist/$name.jar").toURL()
                logger.lifecycle("Lade $url")
                url.openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

val pluginJars = files(requiredPlugins.map { name -> josmPluginDir.map { it.file("$name.jar") } })
    .builtBy(downloadJosmPlugins)

val josmRuntime: Configuration = configurations.create("josmRuntime")

dependencies {
    compileOnly("org.openstreetmap.josm:josm:$josmVersion")
    compileOnly(pluginJars)

    testImplementation("org.openstreetmap.josm:josm:$josmVersion")
    testImplementation(pluginJars)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    josmRuntime("org.openstreetmap.josm:josm:$josmVersion")
}

java {
    // Kompiliert wird mit einem JDK 21 (wird gesucht bzw. automatisch geladen), erzeugt wird Bytecode für Java 11.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // JOSM läuft ab Java 11, daher wird auch das Plugin für Java 11 gebaut.
    options.release.set(11)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-options", "-Xlint:-processing"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("alkisselector.jar")
    manifest {
        val attrs = mutableMapOf(
            "Plugin-Class" to "de.alkisselector.AlkisSelectorPlugin",
            "Plugin-Description" to "Halbautomatische Übernahme von ALKIS-Gebäuden aus frei konfigurierbaren WFS-Diensten " +
                "mit OSM-Abgleich und Orthophoto-Plausibilisierung.",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Mainversion" to josmMinVersion,
            "Plugin-Requires" to requiredPlugins.joinToString(";"),
            "Plugin-Canloadatruntime" to "true",
            "Plugin-Icon" to "images/alkisselector.svg",
            "Plugin-Date" to Instant.now().toString(),
            "Author" to providers.gradleProperty("plugin.author").get()
        )
        val link = providers.gradleProperty("plugin.link").getOrElse("")
        if (link.isNotBlank()) attrs["Plugin-Link"] = link
        attributes(attrs)
    }
}

// JOSM benötigt diese Freigaben (sonst stehen sie im Manifest der josm.jar, das bei -cp nicht greift).
val josmJvmArgs = listOf(
    "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
    "--add-exports=java.desktop/com.sun.imageio.spi=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.imageio.spi=ALL-UNNAMED",
    "--add-opens=java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED",
    "--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED"
)

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // Online-Tests gegen echte Dienste: ./gradlew test -Donline=true
    systemProperty("online", providers.systemProperty("online").getOrElse("false"))
    testLogging {
        showStandardStreams = providers.systemProperty("online").getOrElse("false") == "true"
    }
    systemProperty("josm.home", layout.buildDirectory.dir("josm-test-home").get().asFile.absolutePath)
    jvmArgs(josmJvmArgs)
    // Die Selbstprüfung (assert) in algs4.AssignmentProblem aus utilsplugin2 schlägt wegen Rundung fehl;
    // in JOSM sind Assertions ohnehin aus. Für unseren eigenen Code bleiben sie aktiv.
    jvmArgs("-da:edu.princeton.cs.algs4...")
}

// ---------------------------------------------------------------------------
// runJosm: startet JOSM mit einem eigenen, sauberen Profil unter build/josm-home,
// in dem nur dieses Plugin und seine Abhängigkeiten aktiviert sind.
// ---------------------------------------------------------------------------
val josmHome = layout.buildDirectory.dir("josm-home")

val prepareJosmHome = tasks.register<Copy>("prepareJosmHome") {
    description = "Kopiert das Plugin und seine Abhängigkeiten in das JOSM-Testprofil."
    from(tasks.jar)
    from(pluginJars)
    into(josmHome.map { it.dir("plugins") })
    val home = josmHome
    val pluginNames = listOf("alkisselector") + requiredPlugins
    doLast {
        val prefs = home.get().file("preferences.xml").asFile
        if (!prefs.exists()) {
            val entries = pluginNames.joinToString("\n") { "    <entry value=\"$it\"/>" }
            prefs.writeText(
                """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<preferences xmlns="http://josm.openstreetmap.de/preferences-1.0" version="$josmVersion">
                |  <list key="plugins">
                |$entries
                |  </list>
                |  <tag key="pluginmanager.version-based-update.policy" value="never"/>
                |  <tag key="pluginmanager.time-based-update.policy" value="never"/>
                |</preferences>
                |""".trimMargin(),
                Charsets.UTF_8
            )
        }
    }
}

tasks.register<JavaExec>("runJosm") {
    group = "josm"
    description = "Startet JOSM mit dem ALKISselector-Plugin."
    dependsOn(prepareJosmHome)
    classpath = josmRuntime
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    mainClass.set("org.openstreetmap.josm.gui.MainApplication")
    systemProperty("josm.home", josmHome.get().asFile.absolutePath)
    jvmArgs(josmJvmArgs)
    // Optionale JOSM-Startargumente, z. B. -Pjosm.args="--download=51.96,7.61,51.965,7.62"
    val extra = providers.gradleProperty("josm.args").getOrElse("")
    if (extra.isNotBlank()) args(extra.split(" ").filter { it.isNotBlank() })
}
