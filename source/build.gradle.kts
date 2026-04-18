plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.fraud"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // Logging
        "implementation"("org.slf4j:slf4j-api:2.0.16")
        
        // Lombok
        "compileOnly"("org.projectlombok:lombok:1.18.34")
        "annotationProcessor"("org.projectlombok:lombok:1.18.34")
        
        // Testing
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
