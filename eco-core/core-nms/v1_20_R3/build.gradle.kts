group = "com.willfp"
version = rootProject.version

val spigotVersion = "1.20.3-R0.1-SNAPSHOT"

dependencies {
    compileOnly("org.spigotmc:spigot:$spigotVersion")
}

configurations.compileOnly {
    resolutionStrategy {
        force("org.spigotmc:spigot:$spigotVersion")
    }
}