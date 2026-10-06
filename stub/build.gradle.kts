plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.grachevskaya.asya.stub"
}

dependencies {
    implementation(libs.androidx.annotation.jvm)
}
