plugins {
    `java-library`
}

dependencies {
    // Jackson for JSON
    api("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.1")
    
    // Validation
    api("jakarta.validation:jakarta.validation-api:3.1.0")
}
