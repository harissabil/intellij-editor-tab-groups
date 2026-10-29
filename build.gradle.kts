import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Build against the platform that Android Studio is based on, see docs/compatibility.md.
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// Release tags have no "v" prefix, so the changelog's version links must not add one.
changelog {
    versionPrefix = ""
}

// Run the plugin in a locally installed Android Studio:
//   ./gradlew runAndroidStudio -PandroidStudioPath="C:/Program Files/Android/Android Studio"
// The task is only registered when the property is set, so CI is unaffected.
providers.gradleProperty("androidStudioPath").orNull?.let { studioPath ->
    intellijPlatformTesting.runIde.register("runAndroidStudio") {
        localPath = file(studioPath)
    }
}
