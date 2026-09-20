pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AdGuardNative"
include(":app", ":core")
// QH-P06-01：自有跨应用探针（测试专用，不进入主APK依赖；production 构建不引用）
include(":probe-source", ":probe-destination")
