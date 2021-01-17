import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "1.4.21"
	application
}

application {
	mainClassName = "MainKt"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
	jcenter()
}

tasks.withType<KotlinCompile> {
	kotlinOptions.jvmTarget = "1.8"
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("org.ktorm:ktorm-support-sqlserver:3.3.0")
	implementation("mysql", "mysql-connector-java","8.0.19")
	implementation("com.jessecorbett:diskord-jvm:1.8.0")
	implementation("io.github.microutils:kotlin-logging:2.0.4")
	implementation("org.slf4j:slf4j-simple:1.6.1")
}
val fatJar = task("fatJar", type = Jar::class) {
	baseName = "${project.name}-fatjar"
	manifest {
		attributes["Implementation-Title"] = "Gradle Jar File Example"
		attributes["Implementation-Version"] = version
		attributes["Main-Class"] = "MainKt"
	}
	from(configurations.runtimeClasspath.get().map({ if (it.isDirectory) it else zipTree(it) }))
	with(tasks.jar.get() as CopySpec)
}
tasks {
	"build" {
		dependsOn(fatJar)
	}
}