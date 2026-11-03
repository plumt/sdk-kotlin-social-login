import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.sociallogin.google"
    compileSdk = 36

    defaultConfig {
        // Credential Manager 는 21 이지만 다른 수단(카카오 SDK v2)과 맞춰 23 으로 둔다
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 공개 API 가 core 의 타입(SocialLoginProvider·SocialLoginResult)을 돌려준다
    api(project(":social-login-core"))
    // Credential Manager·googleid 타입은 공개 API 에 드러내지 않는다. 앱이 쓰려면 직접 의존한다
    implementation(libs.androidx.credentials)
    // Android 13 이하에서 Credential Manager 가 Google Play 서비스로 동작하게 한다
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "social-login-google", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("social-login-google")
        description.set("Google 로그인 Provider — Credential Manager·Sign in with Google ID 토큰, nonce, 오류 분류 (비공식)")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
                // Credential Manager 직결 어댑터 — 실제 Google Play 서비스·계정·망이 필요해 단위 테스트가 불가능하다.
                // 옵션 생성·자격 증명 해석·오류 분류는 가짜 게이트웨이로 따로 검증한다
                classes("net.plumt.sociallogin.google.internal.DefaultGoogleGateway*")
            }
        }
        verify {
            rule {
                // 라인 커버리지 90퍼센트 미만이면 koverVerify 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") { dependsOn("koverVerify") }
