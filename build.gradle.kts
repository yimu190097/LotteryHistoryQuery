plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false

    // ============ 开发 & 质量 插件 ============
    // Detekt: Kotlin 静态代码分析（发现坏味道、潜在 bug、复杂度超标）
    id("io.gitlab.arturbosch.detekt") version "1.23.6"

    // Spotless: 代码/构建脚本 统一格式化（Kotlin / XML / Gradle / ProGuard）
    id("com.diffplug.spotless") version "6.25.0"

    // 依赖版本更新检查：一键识别过期依赖 & 插件
    id("com.github.ben-manes.versions") version "0.51.0"

    // JaCoCo: 单元测试覆盖率报告（Gradle 内置插件，无需版本号）
    jacoco
}

// ============ Spotless 全局格式规则 ============
spotless {
    // Kotlin 源码（含 Gradle Kotlin DSL *.kts）统一规范
    kotlin {
        target("**/*.kt", "**/*.kts")
        targetExclude("**/build/**/*.kt", "**/build/**/*.kts")
        // ktfmt：与 IntelliJ 默认格式化风格一致的格式化引擎
        ktfmt("0.46").kotlinlangStyle()
        // 统一行尾 & 文件末尾空行
        trimTrailingWhitespace()
        endWithNewline()
    }

    // Android XML 资源（layout / drawable / values / manifest）
    format("xml") {
        target("**/*.xml")
        targetExclude("**/build/**/*.xml")
        // 统一缩进 4 空格、去掉末尾空白、文末换行
        indentWithSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
    }

    // ProGuard / 混淆规则文件
    format("proguard") {
        target("**/proguard-rules.pro", "**/*.pro")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ============ Detekt 全局静态分析配置 ============
detekt {
    buildUponDefaultConfig = true          // 基于官方默认规则集
    allRules = false                        // 不启用实验性规则，避免误报
    parallel = true                         // 并行分析多模块，提速
    ignoreFailures = false                  // 发现违规即失败，守住质量底线
    autoCorrect = false                     // 不自动修正，防止误改业务代码
    // 报告输出目录：build/reports/detekt/
    reportsDir = layout.buildDirectory.dir("reports/detekt").get().asFile
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        xml.required.set(true)   // 给 CI / IDE 插件解析用
        html.required.set(true)  // 人类可读报告
        md.required.set(true)    // Markdown 方便粘贴到 PR 评论
        txt.required.set(false)
    }
}

// ============ JaCoCo 统一版本（Android Gradle Plugin 内部也会用到 Jacoco） ============
jacoco {
    toolVersion = "0.8.11"
}

// ============ 依赖版本检查插件：过滤非稳定版 ============
tasks.named<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>("dependencyUpdates") {
    resolutionStrategy {
        componentSelection {
            all {
                val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "eap", "snapshot")
                    .any { qualifier -> candidate.version.lowercase().contains(qualifier) }
                if (rejected) {
                    reject("Release candidate / preview / snapshot")
                }
            }
        }
    }
    checkForGradleUpdate = true
    outputFormatter = "html,json,text"
    outputDir = "build/reports/dependency-updates"
}
