pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// O AAR do sherpa-onnx (voz neural no aparelho) não está no Maven Central.
// Ele é baixado uma vez da release oficial, conferido pelo SHA-256 e servido de um repositório local.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val thirdParty = File(rootDir, ".thirdparty/maven")
run {
    val dir = File(thirdParty, "com/k2fsa/sherpa/onnx/sherpa-onnx-android/$sherpaVersion")
    val aar = File(dir, "sherpa-onnx-android-$sherpaVersion.aar")
    if (!aar.isFile) {
        dir.mkdirs()
        val tmp = File(dir, "baixando.tmp")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        java.net.URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
        if (digest != sherpaSha256) {
            tmp.delete()
            error("sherpa-onnx $sherpaVersion: SHA-256 $digest não bate com $sherpaSha256")
        }
        tmp.renameTo(aar)
    }
    File(dir, "sherpa-onnx-android-$sherpaVersion.pom").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.k2fsa.sherpa.onnx</groupId>
          <artifactId>sherpa-onnx-android</artifactId>
          <version>$sherpaVersion</version>
          <packaging>aar</packaging>
        </project>
        """.trimIndent(),
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = thirdParty.toURI()
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "blico"

include(
    ":core",
    ":app",
    ":brain",
    ":voice",
    ":executor",
    ":skills",
    ":memory",
    ":confirmation",
    ":testkit",
)
