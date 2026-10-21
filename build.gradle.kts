plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.mds"
version = "0.0.1-SNAPSHOT"
description = "Crypto price aggregator backed by XChange and InfluxDB"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val xchangeVersion = "6.0.0"
val influxClientVersion = "8.0.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Exchange connectivity
    implementation("org.knowm.xchange:xchange-core:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-binance:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-kraken:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-bitstamp:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-kucoin:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-bitfinex:$xchangeVersion")

    // Persistence
    implementation("com.influxdb:influxdb-client-java:$influxClientVersion")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
