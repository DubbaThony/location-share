plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // BouncyCastle for Ed25519 — Android's platform Signature provider
    // only gained Ed25519 in API 31 (Android 12); our minSdk is 29, so we
    // bundle BC and call its primitives directly. The `jdk15to18` variant
    // targets older bytecode and is the well-tested choice for Android.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
}
