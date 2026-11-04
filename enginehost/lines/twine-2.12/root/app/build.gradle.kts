plugins { id("com.android.library") }

// The Twine core: its own wrapper, TwinePlugin, and nothing of the HTML core's. The owner
// (Droidtop/tracker#28, 2026-11-04): "I have REPEATEDLY demanded we not unify cores like that".
// Both live in this repository as separate lines; this line compiles only dev.enginehost.plugin.web.
android {
    namespace = "dev.enginehost.plugin.web"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.exclude("dev/enginehost/plugin/html/**")
}

dependencies {
    compileOnly(project(":api"))
}
