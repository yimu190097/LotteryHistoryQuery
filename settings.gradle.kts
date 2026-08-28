pluginManagement {
    repositories {
        // 官方仓库优先（CI/海外主机可达）；阿里云/腾讯镜像作兜底，避免镜像 502 导致构建失败
        google()
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 官方仓库优先（CI/海外主机可达）；阿里云/腾讯镜像作兜底，避免镜像 502 导致构建失败
        google()
        mavenCentral()
        maven { url = uri("https://packages.getstream.io/android/maven") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    }
}

rootProject.name = "LotteryHistoryQuery"
include(":app")
include(":admin-app")
