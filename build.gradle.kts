import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
}