plugins {
    java
    id("io.gatling.gradle") version "3.11.5.2"
}

repositories {
    mavenCentral()
}

dependencies {
    gatling("io.gatling.highcharts:gatling-charts-highcharts:3.11.5")
}

gatling {
    logLevel = "WARN"
}
