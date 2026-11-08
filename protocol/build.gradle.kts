plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

tasks.register<JavaExec>("demoServer") {
    description = "Runs the USB/IP server on the JVM with a fake device (for testing clients)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.zk.usbip.protocol.DemoServerKt")
}
