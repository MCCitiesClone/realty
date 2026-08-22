plugins {
    `java-library`
    `realty-conventions`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.3.1"
}

dependencies {
    api(project(":realty-paper-api"))
    implementation(project(":realty-backend"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.15") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    implementation("org.enginehub:squirrelid:0.3.2") {
        isTransitive = false
    }
    implementation("org.xerial:sqlite-jdbc:3.46.1.0") {
        isTransitive = false
    }
    compileOnly("io.paradaux:treasury-api:2.2.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    implementation("org.incendo:cloud-paper:2.0.0-beta.10")
    implementation("com.github.MCCitiesClone:hibernia-framework:0e3d62e") {
        // Paper supplies slf4j at runtime. Bundling reflections' slf4j-api 1.7.32 would either
        // clash with Paper's 2.x or, if relocated, bind the framework's logging to a
        // binding-less NOP logger that silently drops its warnings.
        exclude(group = "org.slf4j", module = "slf4j-api")
        // Annotation-only artifacts: retained solely as compile-time metadata, so shading them
        // adds weight to the jar and nothing else.
        exclude(group = "com.google.code.findbugs", module = "jsr305")
        exclude(group = "org.checkerframework", module = "checker-qual")
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    // Shared module system, schema migrations and formatting helpers. Deliberately NOT relocated in
    // shadowJar: module jars are compiled against these types and loaded into this plugin's class
    // loader, so the names must match.
    implementation("com.minecraftcitiesnetwork:plugin-infrastructure:1.0.0-SNAPSHOT")

    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("io.paradaux:treasury-api:2.2.1-SNAPSHOT")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation("com.sk89q.worldguard:worldguard-bukkit:7.0.15") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

tasks {

    test {
        val byteBuddyAgent = configurations.testRuntimeClasspath.get().files.find { it.name.contains("byte-buddy-agent") }
        if (byteBuddyAgent != null) {
            jvmArgs("-javaagent:$byteBuddyAgent")
        }
    }

    shadowJar {
        val base = "io.github.md5sha256.realty.libraries"
        relocate("org.mariadb", "${base}.org.mariadb")
        relocate("org.mybatis", "${base}.org.mybatis")
        relocate("org.yaml", "${base}.org.yaml")
        relocate("org.apache.ibatis", "${base}.org.apache.ibatis")
        relocate("org.jetbrains.annotations", "${base}.org.jetbrains.annotations")
        relocate("org.intellij.lang", "${base}.org.intellij.lang")
        relocate("net.kyori.option", "${base}.net.kyori.option")
        // Still bundled by Cloud, not by Configurate; goes when Cloud does.
        relocate("io.leangen.geantyref", "${base}.io.leangen.geantyref")
        relocate("org.incendo.cloud", "${base}.org.incendo.cloud")
        relocate("org.enginehub.squirrelid", "${base}.org.enginehub.squirrelid")
        relocate("org.sqlite", "${base}.org.sqlite")
        // Hibernia and its DI stack. Relocated — unlike plugin-infrastructure above — because no
        // module jar references these types: the adapters exchange only Realty's own classes and
        // pre-rendered Components with core. Relocating also keeps Realty's copy of
        // io.paradaux.* off the classpath Treasury joins into this plugin (`join-classpath: true`),
        // where two unrelocated copies of the same framework could otherwise resolve against
        // each other.
        relocate("io.paradaux.hibernia", "${base}.io.paradaux.hibernia")
        relocate("com.google.inject", "${base}.com.google.inject")
        relocate("com.google.common", "${base}.com.google.common")
        relocate("com.google.thirdparty", "${base}.com.google.thirdparty")
        relocate("org.reflections", "${base}.org.reflections")
        relocate("javassist", "${base}.javassist")
        relocate("jakarta.inject", "${base}.jakarta.inject")
        relocate("org.aopalliance", "${base}.org.aopalliance")
        mergeServiceFiles()

        dependsOn(":realty-paper-adapters:chat-adapter:shadowJar")
        from(project(":realty-paper-adapters:chat-adapter")
                .tasks.named("shadowJar").map { it.outputs.files.singleFile }) {
            into("modules")
            rename { "chat-adapter.jar" }
        }
    }

    processResources {
        val projectVersion = version
        filesMatching("paper-plugin.yml") {
            expand("version" to projectVersion)
        }
    }

    runServer {
        dependsOn(":realty-paper-adapters:chat-adapter:shadowJar")
        // EssentialsX is downloaded below, so stage the adapter that pairs with it too — otherwise
        // the spec's Essentials smoke test cannot be run as written.
        dependsOn(":realty-paper-adapters:essentials-adapter:shadowJar")
        doFirst {
            val moduleDir = project.layout.projectDirectory.dir("run/plugins/Realty/modules").asFile
            moduleDir.mkdirs()
            val chatAdapterJar = project(":realty-paper-adapters:chat-adapter")
                    .tasks.named("shadowJar").get().outputs.files.singleFile
            chatAdapterJar.copyTo(moduleDir.resolve("chat-adapter.jar"), overwrite = true)
            val essentialsAdapterJar = project(":realty-paper-adapters:essentials-adapter")
                    .tasks.named("shadowJar").get().outputs.files.singleFile
            essentialsAdapterJar.copyTo(moduleDir.resolve("essentials-adapter.jar"), overwrite = true)
        }
        minecraftVersion("1.21.8")
        downloadPlugins {
            // WorldEdit 7.4.0
            url("https://mediafilez.forgecdn.net/files/7479/274/worldedit-bukkit-7.4.0.jar")
            // WorldGuard 7.0.14
            url("https://mediafilez.forgecdn.net/files/6643/567/worldguard-bukkit-7.0.14-dist.jar")
            // EssX
            url("https://ci.ender.zone/job/EssentialsX/1774/artifact/jars/EssentialsX-2.22.0-dev+74-d7452bf.jar")
            // Vault
            url("https://mediafilez.forgecdn.net/files/3007/470/Vault.jar")
        }
    }
}

// Transitional: lets the one-off messages.yml -> messages.properties conversion run against the
// real classpath. Removed once messages.yml is gone.
tasks.register("printRuntimeCp") {
    // paper-api is compileOnly, so the runtime classpath alone cannot load YamlConfiguration.
    val cp = sourceSets["main"].compileClasspath + sourceSets["main"].output
    doLast { println(cp.asPath) }
}
