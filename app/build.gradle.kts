plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    // Detekt 模块级静态分析（分析当前 app 模块下的源码）
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.lottery.history"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lottery.history"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "22.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "Lottery2026"
            keyAlias = "lottery"
            keyPassword = "Lottery2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
            // JaCoCo 覆盖率：允许 debug buildType 生成本地单元测试的执行数据（AGP 8.x 新 API）
            testCoverage {
                enableUnitTestCoverage = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

    testOptions {
        // Robolectric 需要读取项目资源/Manifest，在本地 JVM 上模拟 Android
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // 将 android-all-instrumented 本地路径注入 Robolectric 离线搜索路径，
            // 避免首次运行时从 Maven Central 下载（沙箱环境外网连接不稳定）
            all { testTask ->
                testTask.systemProperty(
                    "robolectric.dependency.repo.url",
                    "file://${rootDir}/../.m2/repository"
                )
                testTask.jvmArgs(
                    // 给 Robolectric maven-resolver 加本地 maven 仓库优先
                    "-Drobolectric.dependency.repo.url=file://${System.getProperty("user.home")}/.m2/repository",
                    "-Drobolectric.offline=false"
                )
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // WorkManager 23:00 auto update
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // 密码哈希（bcrypt，OWASP 推荐，禁止明文存储）
    implementation("org.mindrot:jbcrypt:0.4")

    // 加密存储 token / 登录态
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Excel (xls 97-2003) 解析 —— 官方公开开奖数据表格格式
    // 本地 JAR，避免 Gradle 镜像仓库因网络波动拉取失败
    implementation(files("libs/jxl-2.6.12.jar"))

    // ============ 本地 JVM 单元测试（test）依赖 ============
    // Robolectric：在本地 JVM 上运行 Android 组件，无需模拟器
    // 用途：FlowLayoutKL8InstrumentedTest 在 JVM 上真实调用 FlowLayout.onMeasure/onLayout 验证分行逻辑
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}

// ============ JaCoCo 覆盖率报告（Android debug 本地单元测试） ============
// 使用方式：
//   1. gradle testDebugUnitTest                      先跑单元测试生成 exec 数据
//   2. gradle jacocoDebugUnitTestReport              生成 html/xml/csv 覆盖率报告
//   3. 报告路径：app/build/reports/jacoco/jacocoDebugUnitTestReport/
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val variantName = variant.name
        val capitalizedVariant = variantName.replaceFirstChar(Char::uppercaseChar)
        val buildDirFile = layout.buildDirectory.get().asFile

        tasks.register<JacocoReport>("jacoco${capitalizedVariant}UnitTestReport") {
            group = "Verification"
            description = "生成 ${capitalizedVariant} 本地单元测试的 JaCoCo 覆盖率报告"
            // 先跑测试，确保有覆盖率原始数据
            dependsOn("test${capitalizedVariant}UnitTest")

            // 被分析的字节码目录（排除 R.class / BuildConfig / 数据绑定生成类等）
            classDirectories.setFrom(
                files(
                    fileTree("${buildDirFile}/tmp/kotlin-classes/${variantName}") {
                        exclude(
                            "**/R.class",
                            "**/R$*.class",
                            "**/BuildConfig.*",
                            "**/Manifest*.*",
                            "**/*Test*.*",
                            "**/*\$ViewInjector*.*",
                            "**/*\$ViewBinder*.*",
                            "**/data/**/*Dao_Impl*.class",
                            "**/db/**/*_Impl*.class",
                            "**/databinding/**/*.*"
                        )
                    },
                    fileTree("${buildDirFile}/intermediates/javac/${variantName}/classes") {
                        exclude(
                            "**/R.class",
                            "**/R$*.class",
                            "**/BuildConfig.*",
                            "**/Manifest*.*"
                        )
                    }
                )
            )

            // 源码目录：Kotlin + Java
            sourceDirectories.setFrom(
                files(
                    "src/main/java",
                    "src/main/kotlin",
                    "src/${variantName}/java",
                    "src/${variantName}/kotlin"
                )
            )

            // JaCoCo 执行数据路径：testDebugUnitTest 生成的 .exec 文件
            executionData.setFrom(
                fileTree(buildDirFile) {
                    include(
                        "jacoco/test${capitalizedVariant}UnitTest.exec",
                        "outputs/unit_test_code_coverage/${variantName}UnitTest/test${capitalizedVariant}UnitTest.exec"
                    )
                }
            )

            reports {
                xml.required.set(true)   // 给 CI 覆盖率工具（Codecov / SonarQube）消费
                csv.required.set(false)
                html.required.set(true)  // 人类可读的覆盖率 HTML
                html.outputLocation.set(file("${buildDirFile}/reports/jacoco/jacoco${capitalizedVariant}UnitTestReport/html"))
            }
        }
    }
}
