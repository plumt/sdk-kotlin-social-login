pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 카카오 SDK 는 Maven Central 이 아니라 카카오 저장소에만 있다. 이 저장소에서는 카카오 좌표만 받는다
        maven {
            url = uri("https://devrepo.kakao.com/nexus/content/groups/public/")
            content { includeGroup("com.kakao.sdk") }
        }
    }
}

rootProject.name = "sdk-kotlin-social-login"

include(":social-login-core")
include(":social-login-kakao")
include(":social-login-naver")
include(":social-login-google")
include(":sample")
