import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

val pluginApiVersion = "1.0.1"

android {
    namespace = "ink.sysu.materials"
    compileSdk = 36

    defaultConfig {
        applicationId = "ink.sysu.materials"
        minSdk = 29
        targetSdk = 36
        versionCode = 110
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.sadami3066.deepdeep:plugin-api:$pluginApiVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    compileOnly(platform("androidx.compose:compose-bom:2026.03.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
}

private val SIGNING_FILE_SUFFIX_PRIVATE = ".pem"
private val SIGNING_FILE_SUFFIX_PUBLIC = ".pub"

val signingKeyFile = layout.projectDirectory.file(providers.gradleProperty("pluginSigningKey").getOrElse("keys/plugin-signing"))
val signingPrivateKeyFile = File(signingKeyFile.asFile.parentFile, signingKeyFile.asFile.name + SIGNING_FILE_SUFFIX_PRIVATE)
val signingPublicKeyFile = File(signingKeyFile.asFile.parentFile, signingKeyFile.asFile.name + SIGNING_FILE_SUFFIX_PUBLIC)
val unsignedBundle = layout.buildDirectory.file("plugin/${project.name}.unsigned.ddp")
val signedBundle = layout.buildDirectory.file("plugin/${project.name}.ddp")

val assemblePlugin = tasks.register("assemblePlugin") {
    group = "plugin"
    description = "Repacks the debug APK into an unsigned .ddp (classes*.dex + assets/** + lib/**)."
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/${project.name}-debug.apk")
    val output = unsignedBundle
    inputs.file(apk)
    outputs.file(output)
    doLast {
        val apkFile = apk.get().asFile
        check(apkFile.isFile) { "missing ${apkFile.absolutePath}; run assembleDebug first" }
        val target = output.get().asFile
        target.parentFile.mkdirs()
        if (target.exists()) target.delete()
        ZipOutputStream(target.outputStream().buffered()).use { bundle ->
            ZipFile(apkFile).use { apkZip ->
                apkZip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { entry ->
                        entry.name.matches(DEX_ENTRY) ||
                            entry.name.startsWith("assets/") ||
                            entry.name.matches(NATIVE_ENTRY)
                    }
                    .sortedWith(Comparator { left, right -> compareUtf8(left.name, right.name) })
                    .forEach { entry ->
                        val copy = ZipEntry(entry.name)
                        copy.time = FIXED_ENTRY_TIME
                        bundle.putNextEntry(copy)
                        apkZip.getInputStream(entry).use { it.copyTo(bundle) }
                        bundle.closeEntry()
                    }
            }
        }
        ZipFile(target).use { zip ->
            check(zip.getEntry("assets/plugin.json") != null) {
                "assets/plugin.json is missing; it must live in src/main/assets/plugin.json"
            }
            check(zip.entries().asSequence().any { it.name.matches(DEX_ENTRY) }) {
                "no root classes*.dex in the APK; check minSdk/desugaring configuration"
            }
        }
        logger.lifecycle("Assembled ${target.absolutePath} (sha256=${target.sha256Hex()})")
    }
}

val generateSigningKey = tasks.register("generateSigningKey") {
    group = "plugin"
    description = "Generates the Ed25519 signing key pair used by signPlugin."
    doLast {
        writeEd25519KeyPair(signingKeyFile.asFile)
        logger.lifecycle("Generated ${signingPrivateKeyFile.absolutePath} and ${signingPublicKeyFile.absolutePath}")
        logger.lifecycle("Keep the .pem private and back it up: anyone holding it can publish updates as this publisher.")
    }
}

val signPlugin = tasks.register("signPlugin") {
    group = "plugin"
    description = "Signs the .ddp with an Ed25519 key and writes META-INF/plugin-signature.json."
    dependsOn(assemblePlugin)
    inputs.file(unsignedBundle)
    inputs.files(signingPrivateKeyFile, signingPublicKeyFile).optional()
    outputs.file(signedBundle)
    doLast {
        val source = unsignedBundle.get().asFile
        check(source.isFile) { "missing ${source.absolutePath}; run assemblePlugin first" }
        val keyPair = loadEd25519KeyPair(signingKeyFile.asFile)
        val keyId = ed25519KeyId(keyPair.public)
        val digest = canonicalEntryDigest(source)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(digest)
            sign()
        }
        val target = signedBundle.get().asFile
        target.parentFile.mkdirs()
        if (target.exists()) target.delete()
        copyZipAddingEntry(
            source,
            target,
            SIGNATURE_ENTRY,
            buildSignatureJson(
                keyId = keyId,
                publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                signature = Base64.getEncoder().encodeToString(signature),
            ).toByteArray(StandardCharsets.UTF_8),
        )
        check(verifySignedBundle(target, keyId)) { "signed bundle failed self-verification" }
        logger.lifecycle("Signed ${target.absolutePath}")
        logger.lifecycle("  publisher keyId = $keyId")
        logger.lifecycle("  bundle sha256   = ${target.sha256Hex()}")
        logger.lifecycle("  entry digest    = ${sha256Hex(digest)}")
        logger.lifecycle("  key material    = ${signingKeyFile.asFile.absolutePath}.pem/.pub (never commit the .pem)")
    }
}

val verifyPlugin = tasks.register("verifyPlugin") {
    group = "plugin"
    description = "Verifies META-INF/plugin-signature.json against the canonical entry digest."
    dependsOn(signPlugin)
    doLast {
        val target = signedBundle.get().asFile
        val json = readSignature(target)
        val keyId = jsonField(json, "keyId").orEmpty()
        check(verifySignedBundle(target, keyId)) { "signature verification failed for ${target.absolutePath}" }
        logger.lifecycle("Signature OK: ${target.absolutePath} (keyId=$keyId)")
    }
}

private val SIGNATURE_ENTRY = "META-INF/plugin-signature.json"
private val FIXED_ENTRY_TIME = 315532800000L
private val DEX_ENTRY = Regex("classes[0-9]*\\.dex")
private val NATIVE_ENTRY = Regex("lib/[^/]+/.+")

private fun loadEd25519KeyPair(base: File): KeyPair {
    val privateFile = File(base.parentFile, base.name + SIGNING_FILE_SUFFIX_PRIVATE)
    val publicFile = File(base.parentFile, base.name + SIGNING_FILE_SUFFIX_PUBLIC)
    check(privateFile.isFile && publicFile.isFile) {
        "missing signing key: ${privateFile.absolutePath} or ${publicFile.absolutePath}; " +
            "run ./gradlew generateSigningKey to create one, or pass -PpluginSigningKey=<key path prefix>"
    }
    val factory = KeyFactory.getInstance("Ed25519")
    val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(decodePem(privateFile.readText())))
    val publicKey = factory.generatePublic(X509EncodedKeySpec(decodePem(publicFile.readText())))
    return KeyPair(publicKey, privateKey)
}

private fun writeEd25519KeyPair(base: File) {
    val privateFile = File(base.parentFile, base.name + SIGNING_FILE_SUFFIX_PRIVATE)
    val publicFile = File(base.parentFile, base.name + SIGNING_FILE_SUFFIX_PUBLIC)
    check(!privateFile.exists() && !publicFile.exists()) {
        "signing key already exists at ${privateFile.absolutePath}; delete both ${privateFile.name} and ${publicFile.name} to regenerate"
    }
    privateFile.parentFile.mkdirs()
    val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    privateFile.writeText(encodePem("PRIVATE KEY", keyPair.private.encoded))
    publicFile.writeText(encodePem("PUBLIC KEY", keyPair.public.encoded))
    privateFile.setReadable(false, false)
    privateFile.setReadable(true, true)
    privateFile.setWritable(true, true)
    privateFile.setExecutable(false, false)
}

private fun encodePem(type: String, bytes: ByteArray): String {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII)).encodeToString(bytes)
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
}

private fun decodePem(text: String): ByteArray {
    val body = text.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
    return Base64.getMimeDecoder().decode(body)
}

private fun ed25519KeyId(publicKey: PublicKey): String =
    "ed25519:" + MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).joinToString("") { "%02x".format(it) }

private fun canonicalEntryDigest(archive: File): ByteArray {
    val entries = ZipFile(archive).use { zip ->
        val names = mutableSetOf<String>()
        zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filterNot { it.name.startsWith("META-INF/") }
            .map { entry ->
                validateZipEntryName(entry.name)
                check(names.add(entry.name)) { "duplicate zip entry: ${entry.name}" }
                Triple(entry.name, entry.size, zip.getInputStream(entry).use(::sha256Hex))
            }
            .sortedWith(Comparator { left, right -> compareUtf8(left.first, right.first) })
            .toList()
    }
    val text = buildString {
        entries.forEach { (name, size, sha256) ->
            append(sha256).append(' ').append(size).append(' ').append(name).append('\n')
        }
    }
    return text.toByteArray(StandardCharsets.UTF_8)
}

private fun validateZipEntryName(name: String) {
    check(name.isNotBlank()) { "blank zip entry name" }
    check(!name.startsWith("/")) { "absolute zip entry name: $name" }
    check(!name.contains("..")) { "zip entry name must not contain '..': $name" }
    check('\\' !in name) { "zip entry name must use forward slashes: $name" }
    check('\u0000' !in name) { "zip entry name must not contain NUL: $name" }
    check(':' !in name) { "zip entry name must not contain ':': $name" }
}

private fun compareUtf8(left: String, right: String): Int {
    val a = left.toByteArray(StandardCharsets.UTF_8)
    val b = right.toByteArray(StandardCharsets.UTF_8)
    val limit = minOf(a.size, b.size)
    for (index in 0 until limit) {
        val difference = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return a.size - b.size
}

private fun copyZipAddingEntry(source: File, target: File, entryName: String, payload: ByteArray) {
    ZipOutputStream(target.outputStream().buffered()).use { output ->
        ZipFile(source).use { zip ->
            zip.entries().asSequence().sortedWith(Comparator { left, right -> compareUtf8(left.name, right.name) })
                .forEach { entry ->
                    val copy = ZipEntry(entry.name)
                    copy.time = FIXED_ENTRY_TIME
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
        }
        val signature = ZipEntry(entryName)
        signature.time = FIXED_ENTRY_TIME
        output.putNextEntry(signature)
        output.write(payload)
        output.closeEntry()
    }
}

private fun verifySignedBundle(archive: File, expectedKeyId: String): Boolean {
    if (!archive.isFile) return false
    val json = readSignature(archive)
    if (jsonField(json, "algorithm") != "Ed25519") return false
    if (jsonField(json, "keyId") != expectedKeyId) return false
    val publicKeyDer = runCatching {
        Base64.getDecoder().decode(jsonField(json, "publicKey").orEmpty())
    }.getOrNull() ?: return false
    val publicKey = runCatching {
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyDer))
    }.getOrNull() ?: return false
    if (ed25519KeyId(publicKey) != expectedKeyId) return false
    val signature = runCatching {
        Base64.getDecoder().decode(jsonField(json, "signature").orEmpty())
    }.getOrNull() ?: return false
    return Signature.getInstance("Ed25519").run {
        initVerify(publicKey)
        update(canonicalEntryDigest(archive))
        verify(signature)
    }
}

private fun readSignature(archive: File): String = ZipFile(archive).use { zip ->
    val entry = zip.getEntry(SIGNATURE_ENTRY) ?: error("missing $SIGNATURE_ENTRY in ${archive.absolutePath}")
    zip.getInputStream(entry).bufferedReader().use { it.readText() }
}

private fun buildSignatureJson(keyId: String, publicKey: String, signature: String): String = buildString {
    append("{\n")
    append("  \"algorithm\": \"Ed25519\",\n")
    append("  \"keyId\": \"").append(keyId).append("\",\n")
    append("  \"publicKey\": \"").append(publicKey).append("\",\n")
    append("  \"signature\": \"").append(signature).append("\"\n")
    append("}\n")
}

private fun jsonField(json: String, name: String): String? =
    Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

private fun sha256Hex(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun File.sha256Hex(): String = inputStream().use(::sha256Hex)
