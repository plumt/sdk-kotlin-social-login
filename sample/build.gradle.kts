import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 수단 조합. 키가 flavor 이름이고 값이 넣을 수단 모듈이다
val providerCombos = mapOf(
    "kakao" to listOf("kakao"),
    "naver" to listOf("naver"),
    "google" to listOf("google"),
    "naverKakao" to listOf("naver", "kakao"),
    "naverGoogle" to listOf("naver", "google"),
    "kakaoGoogle" to listOf("kakao", "google"),
    "all" to listOf("kakao", "naver", "google"),
)

// 시그니처가 남아야 하는 Retrofit 서비스 메서드. `원래 클래스#원래 메서드`
val serviceMethodsByVendor = mapOf(
    "kakao" to listOf(
        "com.kakao.sdk.auth.AuthApi#issueAccessToken",
        "com.kakao.sdk.auth.AuthApi#refreshToken",
        "com.kakao.sdk.auth.AuthApi#agt",
        "com.kakao.sdk.user.UserApi#logout",
        "com.kakao.sdk.user.UserApi#unlink",
        "com.kakao.sdk.user.UserApi#checkAccessToken",
    ),
    "naver" to listOf(
        "com.navercorp.nid.oauth.api.NidOAuthLoginService#requestAccessToken",
        "com.navercorp.nid.oauth.api.NidOAuthLoginService#requestRefreshToken",
        "com.navercorp.nid.oauth.api.NidOAuthLoginService#requestDeleteToken",
        "com.navercorp.nid.profile.api.NidProfileService#getProfileMap",
        "com.navercorp.nid.profile.api.NidProfileService#requestApi",
        "com.navercorp.nid.scheme.api.NidSchemeService#requestSchemeLog",
    ),
)

// `@JsonAdapter` 가 리플렉션으로 만드는 Gson TypeAdapter. 인자 없는 생성자가 남아야 한다
val typeAdaptersByVendor = mapOf(
    "kakao" to listOf(
        "com.kakao.sdk.common.json.KakaoIntDateTypeAdapter",
        "com.kakao.sdk.common.json.MapToQueryAdapter",
    ),
)

// 발행하지 않는 확인용 앱. 소비 앱과 같은 조건(R8 전체 모드 + 리소스 축소, 앱 규칙은 기본 파일뿐)으로 수단 조합마다 release 를 만들어
// 각 모듈의 소비자 규칙만으로 빌드가 되고 벤더 SDK 의 리플렉션 대상이 남는지 본다. 기기 없이 dex·mapping 만 읽는다
android {
    namespace = "net.plumt.sociallogin.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.plumt.sociallogin.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // 확인용이라 debug 키로 서명한다(CI 는 AGP 가 debug 키를 만든다)
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // 수단 조합 하나가 flavor 하나다. 앞선 조합의 규칙이 다른 수단의 누락을 우연히 가리므로(옛 네이버+카카오 조합 등) 전부 따로 만든다
    flavorDimensions += "providers"
    productFlavors {
        providerCombos.keys.forEach { create(it) { dimension = "providers" } }
    }

    sourceSets {
        providerCombos.forEach { (combo, vendors) ->
            getByName(combo).java.srcDirs(vendors.map { "src/provider-$it/kotlin" })
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    providerCombos.forEach { (combo, vendors) ->
        vendors.forEach { add("${combo}Implementation", project(":social-login-$it")) }
    }
}

// 소비 앱이 OkHttp 를 올렸을 때의 해석. 벤더 SDK 가 끌어오는 버전과 참조하는 선택 TLS 제공자가 달라 CI 가 따로 확인한다
providers.gradleProperty("okhttpCompatVersion").orNull?.let { version ->
    configurations.configureEach {
        resolutionStrategy.force("com.squareup.okhttp3:okhttp:$version")
    }
}

/**
 * release APK 의 dex 와 mapping 으로 소비자 규칙의 효과를 확인한다.
 * - Retrofit 서비스 메서드: 제네릭 시그니처가 지워지면 호출 시점에 Retrofit 이 `Call return type must be parameterized`·
 *   `ClassCastException` 으로 실패한다.
 * - Gson TypeAdapter: `@JsonAdapter` 가 리플렉션으로 만드는데 생성자가 지워지면 안 된다.
 * - Extension: 서로 다른 클래스가 한 클래스로 합쳐지면 `SignInOptions` 의 같은 클래스 검사에 걸린다.
 */
abstract class VerifyR8Output : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:InputFile
    abstract val mapping: RegularFileProperty

    @get:InputFile
    abstract val dexdump: RegularFileProperty

    /** `<원래 클래스>#<원래 메서드>`. dex 에 남아 있고 제네릭 시그니처가 있어야 한다 */
    @get:Input
    abstract val requiredServiceMethods: ListProperty<String>

    /** 원래 클래스 이름. 인자 없는 생성자가 dex 에 남아 있어야 한다 */
    @get:Input
    abstract val requiredConstructors: ListProperty<String>

    /** 원래 클래스 이름. 서로 다른 클래스로 남아 있어야 한다 */
    @get:Input
    abstract val distinctClasses: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    private class ServiceMethod(val owner: String, val name: String, val signature: String?, val generic: Boolean)

    @TaskAction
    fun verify() {
        val classes = HashMap<String, String>()
        val methods = HashMap<String, HashMap<String, MutableSet<String>>>()
        parseMapping(classes, methods)

        val artifacts = checkNotNull(loader.get().load(apkDirectory.get())) { "APK 목록을 읽지 못했다: ${apkDirectory.get()}" }
        val apk = File(artifacts.elements.single().outputFile)
        val httpAnnotations = classes.filterKeys { it.startsWith("retrofit2.http.") }.values
            .map { "L" + it.replace('.', '/') + ";" }.toSet()
        val services = ArrayList<ServiceMethod>()
        val constructors = HashSet<String>()
        val dexClasses = HashSet<String>()
        readDex(apk, httpAnnotations, services, constructors, dexClasses)

        val problems = ArrayList<String>()
        val lines = ArrayList<String>()
        services.filterNot { it.generic }.forEach {
            problems += "제네릭 시그니처 없음: ${it.owner}.${it.name} ${it.signature ?: "(Signature 없음)"}"
        }
        requiredServiceMethods.get().forEach { required ->
            val owner = required.substringBefore('#')
            val name = required.substringAfter('#')
            val obfuscated = classes[owner]
            val names = methods[owner]?.get(name).orEmpty()
            val found = services.filter { it.owner == obfuscated && it.name in names }
            when {
                found.isEmpty() -> problems += "서비스 메서드가 dex 에 없다: $required"
                else -> lines += "$required: ${found.joinToString { if (it.generic) "OK ${it.signature}" else "RAW ${it.signature}" }}"
            }
        }
        requiredConstructors.get().forEach { name ->
            val obfuscated = classes[name]
            when {
                obfuscated == null || obfuscated !in dexClasses -> problems += "클래스 없음: $name"
                obfuscated !in constructors -> problems += "생성자 없음: $name"
                else -> lines += "$name: 생성자 OK"
            }
        }
        val distinct = distinctClasses.get().map { name ->
            val obfuscated = classes[name]
            if (obfuscated == null || obfuscated !in dexClasses) problems += "클래스 없음: $name"
            obfuscated
        }
        if (distinct.toSet().size != distinct.size) problems += "서로 다른 클래스가 합쳐졌다: ${distinctClasses.get()} -> $distinct"
        lines += "구분 유지: ${distinctClasses.get().zip(distinct)}"

        report.get().asFile.writeText(
            "apk=${apk.name}\n서비스 메서드 ${services.size}, 제네릭 소실 ${services.count { !it.generic }}\n" +
                lines.joinToString("\n") + "\n" + problems.joinToString("\n") { "문제: $it" } + "\n",
        )
        check(problems.isEmpty()) {
            "R8 뒤의 산출물이 소비자 규칙의 약속과 다르다(각 모듈의 consumer-rules.pro 확인):\n" + problems.joinToString("\n")
        }
    }

    /** mapping 의 `원래 -> 난독화` 를 읽는다. 메서드는 원래 이름 → 난독화 이름들(오버로드가 있으면 여럿) */
    private fun parseMapping(
        classes: MutableMap<String, String>,
        methods: MutableMap<String, HashMap<String, MutableSet<String>>>,
    ) {
        val member = Regex("""^\s+(?:\d+:\d+:)?\S+ ([^\s(]+)\(.*\)(?::\d+(?::\d+)?)? -> (\S+)$""")
        var current: String? = null
        mapping.get().asFile.forEachLine { line ->
            if (line.startsWith("#")) return@forEachLine
            if (!line.startsWith(" ") && line.endsWith(":") && " -> " in line) {
                val (original, obfuscated) = line.removeSuffix(":").split(" -> ")
                classes[original] = obfuscated
                current = original
            } else {
                val match = member.find(line) ?: return@forEachLine
                methods.getOrPut(current ?: return@forEachLine) { HashMap() }
                    .getOrPut(match.groupValues[1]) { HashSet() } += match.groupValues[2]
            }
        }
    }

    private fun readDex(
        apk: File,
        httpAnnotations: Set<String>,
        services: MutableList<ServiceMethod>,
        constructors: MutableSet<String>,
        dexClasses: MutableSet<String>,
    ) {
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) }.forEach { entry ->
                val dex = File(work, entry.name)
                zip.getInputStream(entry).use { input -> dex.outputStream().use { input.copyTo(it) } }
                val process = ProcessBuilder(dexdump.get().asFile.absolutePath, "-a", dex.absolutePath)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "dexdump 실패: ${entry.name}" }
                parseDump(output, httpAnnotations, services, constructors, dexClasses)
            }
        }
    }

    private fun parseDump(
        dump: String,
        httpAnnotations: Set<String>,
        services: MutableList<ServiceMethod>,
        constructors: MutableSet<String>,
        dexClasses: MutableSet<String>,
    ) {
        val methodAnnotations = Regex("""Annotations on method #\d+ '([^']+)'\n((?:  VISIBILITY.*\n)+)""")
        val descriptor = Regex("""Class descriptor\s*:\s*'L([^;]+);'""")
        dump.split(Regex("(?m)(?=^Class #\\d+ annotations:|^Class #\\d+\\s+-)")).forEach { block ->
            val name = descriptor.find(block)?.groupValues?.get(1)?.replace('/', '.')
            if (name != null) {
                dexClasses += name
                if (Regex("""(?s)Direct methods.*?name\s*:\s*'<init>'\s*\n\s*type\s*:\s*'\(\)V'.*?Virtual methods""").containsMatchIn(block)) {
                    constructors += name
                }
            }
        }
        dump.split(Regex("(?m)(?=^Class #\\d+ annotations:)")).forEach { block ->
            val owner = descriptor.find(block)?.groupValues?.get(1)?.replace('/', '.') ?: return@forEach
            methodAnnotations.findAll(block).forEach { annotated ->
                val body = annotated.groupValues[2]
                if (httpAnnotations.none { it in body }) return@forEach
                val raw = Regex("""Ldalvik/annotation/Signature; value=\{(.*)\}""").find(body)?.groupValues?.get(1)
                val signature = raw?.let { text -> Regex("\"([^\"]*)\"").findAll(text).joinToString("") { it.groupValues[1] } }
                services += ServiceMethod(owner, annotated.groupValues[1], signature, signature != null && isGeneric(signature))
            }
        }
    }

    /** Retrofit 이 읽는 자리에 타입 인자가 있는지. 일반 메서드는 반환형, suspend 는 반환형이 Object 이고 마지막 인자(Continuation)다 */
    private fun isGeneric(signature: String): Boolean {
        val returned = signature.substringAfterLast(')')
        return if (returned == "Ljava/lang/Object;") signature.substringBefore(')').endsWith(">;") else '<' in returned
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
    onVariants(selector().withBuildType("release")) { variant ->
        val combo = checkNotNull(variant.flavorName)
        val vendors = providerCombos.getValue(combo)
        val sdk = androidComponents.sdkComponents.sdkDirectory
        val verify = tasks.register<VerifyR8Output>("verify${combo.replaceFirstChar { it.uppercase() }}ReleaseR8") {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            dexdump.set(sdk.map { it.file("build-tools/${android.buildToolsVersion}/dexdump") })
            requiredServiceMethods.set(vendors.flatMap { serviceMethodsByVendor[it].orEmpty() })
            requiredConstructors.set(vendors.flatMap { typeAdaptersByVendor[it].orEmpty() })
            distinctClasses.set(
                listOf(
                    "net.plumt.sociallogin.sample.FirstExtension",
                    "net.plumt.sociallogin.sample.SecondExtension",
                ),
            )
            report.set(layout.buildDirectory.file("reports/r8-verify/$combo.txt"))
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
