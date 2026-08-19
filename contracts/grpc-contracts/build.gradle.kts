import com.google.protobuf.gradle.*
import org.gradle.api.plugins.quality.Checkstyle

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.5"
}

// Keep code generation/runtime aligned with the current supported gRPC Java release line.
val grpcVersion = "1.83.1"
val protobufVersion = "4.34.2"

dependencies {
    // protoc 4.34 emits runtime-version checks; pin the same protobuf runtime instead of relying
    // on grpc-protobuf's older transitive version.
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc")
            }
        }
    }
}

// Generated protobuf/grpc Java is deterministic build output, not hand-maintained project source.
// Compile it and package it, but keep human-source Checkstyle focused on src/.
tasks.withType<Checkstyle>().configureEach {
    source = fileTree("src") {
        include("**/*.java")
    }
}
