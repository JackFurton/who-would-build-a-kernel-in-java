plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "duke.compiler.Main"
    applicationName = "dukec"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // Compiler tests compile the real kernel sources.
    systemProperty("duke.kernelSources", rootProject.file("kernel/src").absolutePath)
    inputs.dir(rootProject.file("kernel/src"))
}

// jsc, the JavaScript compiler, ships in the same jar as dukec with its own launcher script.
val jscScripts = tasks.register<CreateStartScripts>("jscScripts") {
    applicationName = "jsc"
    mainClass = "duke.js.Main"
    outputDir = layout.buildDirectory.dir("jsc-scripts").get().asFile
    classpath = tasks.jar.get().outputs.files + configurations.runtimeClasspath.get()
}

distributions.main {
    contents {
        from(jscScripts) {
            into("bin")
        }
    }
}
