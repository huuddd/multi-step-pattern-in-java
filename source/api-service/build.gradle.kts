plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common"))
    
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    
    // Database
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    
    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    
    // Kafka
    implementation("org.springframework.kafka:spring-kafka")
    
    // RabbitMQ
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    
    // Rate Limiting
    implementation("com.google.guava:guava:33.0.0-jre")
    
    // Metrics
    implementation("io.micrometer:micrometer-registry-prometheus")
    
    // JSON logging
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    
    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2:2.3.232")
}
