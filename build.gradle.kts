// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.10" apply false
    id("com.google.dagger.hilt.android") version "2.48" apply false
    id("com.android.test") version "8.5.0" apply false
    id("com.google.devtools.ksp") version "2.0.10-1.0.24" apply false
    id("io.realm.kotlin") version "2.1.0" apply false
}

