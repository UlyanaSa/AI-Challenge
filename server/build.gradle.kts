plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
   // alias(libs.plugins.shadow)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

application {
    mainClass = "com.osvin.aichallenge.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(project(":agent"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dotenv.kotlin)
    implementation(libs.logback)
    // MCP-сервер проекта: инструменты отдаются клиенту по протоколу, а не подсказкой в промпте.
    implementation(libs.mcp.server)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

// ✅ Альтернативный способ создания fat JAR (без Shadow)
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.ApplicationKt",
            "Implementation-Version" to project.version
        )
    }

    // Включаем все зависимости в JAR
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

//// Настройка shadow JAR
//tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
//    archiveBaseName.set("deepseek-backend")
//    archiveClassifier.set("")
//    archiveVersion.set(project.version.toString())
//
//    manifest {
//        attributes(
//            "Main-Class" to "com.osvin.aichallenge.ApplicationKt",
//            "Implementation-Version" to project.version
//        )
//    }
//
//    mergeServiceFiles()
//    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
//}

// Задача для разработки
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Run in development mode with .env"
    mainClass.set("com.osvin.aichallenge.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Загрузка .env файла
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key, value)
            }
    }
}

/**
 * Демонстрация подключения к MCP: печатает список инструментов сервера.
 *
 * `./gradlew :server:mcpDemo` — клиент поднимает локальный MCP-сервер проекта процессом,
 * проходит рукопожатие и печатает инструменты, которые тот объявил.
 */
val mcpDemo by tasks.registering(Test::class) {
    group = "verification"
    description = "Прогон подключения к MCP-серверу с печатью списка инструментов"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.osvin.aichallenge.mcp.McpDemoTest") }
    // Только печать демонстрации: статусы тестов в консоль не нужны.
    testLogging { events("standardOut") }
    outputs.upToDateWhen { false }
}