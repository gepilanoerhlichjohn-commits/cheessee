plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Plain-main self tests (no JUnit needed): gradle :core:selfTest
tasks.register<JavaExec>("selfTest") {
    dependsOn("testClasses")
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["test"].output
    mainClass.set("app.cheessee.tests.AllTests")
    jvmArgs("-Djava.awt.headless=true")
}
