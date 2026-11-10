plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

// Android(D8)·서버 양쪽에서 읽히도록 바이트코드는 Java 11 로 낸다. 빌드 JDK 는 무엇이든 된다
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(DefaultImpls 없음) — 인터페이스에 함수를 더해도 구현체가 깨지지 않는다
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 동시 호출 제어(Mutex)에만 쓴다. 공개 API 에는 코루틴 타입이 나오지 않는다
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates(artifactId = "social-login-core", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("social-login-core")
        description.set("소셜 로그인 공통 계약 — Provider 인터페이스·결과 모델·오류 분류·Provider 선택기(벤더 SDK 의존 없음)")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        verify {
            rule {
                // 라인 커버리지 90퍼센트 미만이면 koverVerify 가 실패한다
                minBound(90)
            }
        }
    }
}

// 테스트 코드만 코루틴 테스트 API(ExperimentalCoroutinesApi)를 쓴다. 본 코드에는 opt-in 을 걸지 않는다
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("Test")) compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

// 문서 일치 테스트(DocsConsistencyTest)가 읽는 공개 문서 — 바뀌면 테스트를 다시 돌린다
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("README.md"),
        rootProject.file("CHANGELOG.md"),
        rootProject.file("ERRORS.md"),
        rootProject.fileTree(rootProject.projectDir) { include("social-login-*/gradle.properties") },
    ).withPropertyName("publicDocs")
}

tasks.named("check") { dependsOn("koverVerify") }
