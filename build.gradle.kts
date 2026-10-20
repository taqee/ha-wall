plugins {
    id("com.android.application") version "9.1.1" apply false
    // Not applied: only puts a newer Kotlin compiler on the classpath for AGP's built-in Kotlin,
    // because GeckoView pulls kotlin-stdlib 2.4.x.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
