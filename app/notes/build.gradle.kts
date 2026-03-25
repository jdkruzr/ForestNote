plugins {
    id("forestnote.android.application")
}

android {
    namespace = "com.forestnote.app.notes"

    defaultConfig {
        applicationId = "com.forestnote"
    }
}

dependencies {
    implementation(project(":core:ink"))
    implementation(project(":core:format"))
}
