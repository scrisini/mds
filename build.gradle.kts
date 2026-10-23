plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    // the Spring Boot plugin sets up protoc and the gRPC generator with the BOM's versions
    id("com.google.protobuf") version "0.10.0"
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
val awsSdkVersion = "2.55.12"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // gRPC API on its own port (src/main/proto); versions from the Spring Boot BOM
    implementation("org.springframework.boot:spring-boot-starter-grpc-server")

    // Exchange connectivity (WebSocket streams; each pulls in its REST module for metadata)
    implementation("org.knowm.xchange:xchange-stream-core:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-stream-binance:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-stream-kraken:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-stream-bitstamp:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-stream-kucoin:$xchangeVersion")
    implementation("org.knowm.xchange:xchange-stream-bitfinex:$xchangeVersion")

    // Persistence
    implementation("com.influxdb:influxdb-client-java:$influxClientVersion")

    // Cluster: shared candle state and subscriber balancing (version from the Spring Boot BOM)
    implementation("com.hazelcast:hazelcast")

    // Configuration from AWS SSM Parameter Store, over the JDK's HTTP client (no Apache or Netty)
    implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
    implementation("software.amazon.awssdk:ssm") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-grpc-server-test")
    testImplementation("io.grpc:grpc-inprocess")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}


protobuf {
    // protoc comes from the Spring Boot plugin; the gRPC stub generator matches the BOM's grpc-java
    plugins {
        maybeCreate("grpc").artifact =
            "io.grpc:protoc-gen-grpc-java:${dependencyManagement.importedProperties["grpc-java.version"]}"
    }
    generateProtoTasks {
        all().configureEach {
            plugins { maybeCreate("grpc") }
        }
    }
}

// gRPC load client for README "Capacity": ./gradlew grpcLoad -Pargs="<subscribers> <rate> <seconds> ..."
tasks.register<JavaExec>("grpcLoad") {
    description = "Opens gRPC price streams against a running instance and reports throughput"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.mds.load.GrpcLoad"
    jvmArgs("-Xmx2g")
    args = (findProperty("args") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
