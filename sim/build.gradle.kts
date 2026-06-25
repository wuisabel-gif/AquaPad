plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("aquapad.sim.FakeRobotKt")
}

dependencies {
    implementation(project(":protocol"))
    val ktor = "2.3.12"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.61.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
}

kotlin {
    jvmToolchain(17)
}
