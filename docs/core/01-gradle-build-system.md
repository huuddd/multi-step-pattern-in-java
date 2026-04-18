# 01. Gradle Build System

## 🎯 Mục tiêu

- Hiểu Gradle làm gì và tại sao cần
- Đọc và sửa được `build.gradle.kts`
- Biết cách thêm dependency, chạy task
- Debug lỗi build

---

## 1. Tại sao cần Gradle?

### 1.1 Vấn đề khi không có build tool

```bash
# Dự án có 100 file Java, 50 JAR dependencies
javac -cp "lib/a.jar;lib/b.jar;...;lib/z.jar" \
      src/main/java/com/fraud/*.java \
      src/main/java/com/fraud/api/*.java \
      ...

# Đau khổ khi:
# - Thêm dependency → tải JAR, thêm vào classpath
# - Upgrade version → tải JAR mới, xóa JAR cũ
# - Build & run test → command dài vô tận
```

### 1.2 Gradle giải quyết thế nào?

```kotlin
// build.gradle.kts — 1 file config duy nhất
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.3.0")
    implementation("org.postgresql:postgresql:42.7.3")
}

// Gradle tự động:
// 1. Tải JAR từ Maven Central
// 2. Resolve transitive dependencies (a cần b, b cần c)
// 3. Build classpath
// 4. Compile và package
```

---

## 2. Gradle Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         GRADLE BUILD FLOW                              │
└─────────────────────────────────────────────────────────────────────────┘

    Bạn chạy: gradle build
          │
          ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  1. INITIALIZATION                                             │
    │     - Đọc settings.gradle.kts                                  │
    │     - Tìm modules (common, api-service, bench)                │
    └──────────────────────────┬─────────────────────────────────────┘
                               │
                               ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  2. CONFIGURATION                                              │
    │     - Đọc build.gradle.kts mỗi module                          │
    │     - Resolve dependencies                                     │
    │     - Build task graph                                         │
    └──────────────────────────┬─────────────────────────────────────┘
                               │
                               ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  3. EXECUTION                                                  │
    │     - Execute tasks theo thứ tự:                               │
    │       compileJava → processResources → classes → jar → build   │
    └────────────────────────────────────────────────────────────────┘
```

---

## 3. File Structure

### 3.1 Gradle files trong dự án

```
source/
├── settings.gradle.kts          ← Định nghĩa multi-module
├── build.gradle.kts             ← Config root project
├── gradle.properties            ← Properties (version, JVM args)
├── gradlew                      ← Unix script để chạy gradle
├── gradlew.bat                  ← Windows script
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar   ← Gradle wrapper binary
│       └── gradle-wrapper.properties  ← Gradle version
│
├── common/
│   └── build.gradle.kts         ← Config module common
├── api-service/
│   └── build.gradle.kts         ← Config module api-service
└── bench/
    └── build.gradle.kts         ← Config module bench
```

### 3.2 settings.gradle.kts — Multi-module

```kotlin
rootProject.name = "fraud-detection-gateway-source"

include(":common")
include(":api-service")
include(":bench")
```

**Giải thích:**
- Root project tên `fraud-detection-gateway-source`
- Có 3 sub-modules: `common`, `api-service`, `bench`
- Mỗi module có `build.gradle.kts` riêng

### 3.3 build.gradle.kts — Root

```kotlin
// Config áp dụng cho tất cả subprojects
subprojects {
    apply(plugin = "java")
    
    repositories {
        mavenCentral()  // Tải JAR từ Maven Central
    }
    
    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
}
```

### 3.4 build.gradle.kts — api-service

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation(project(":common"))  // Dùng module common
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    
    runtimeOnly("org.postgresql:postgresql")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

**Giải thích từng dòng:**

| Dòng | Ý nghĩa |
|------|---------|
| `plugins { java }` | Apply Java plugin để compile Java code |
| `id("org.springframework.boot")` | Spring Boot plugin để tạo executable JAR |
| `implementation(project(":common"))` | Dùng module `common` như dependency |
| `implementation(...)` | Dependency cần lúc compile và runtime |
| `runtimeOnly(...)` | Chỉ cần lúc runtime, không cần compile |
| `testImplementation(...)` | Chỉ cần cho test |

---

## 4. Dependency Scopes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DEPENDENCY SCOPES                                    │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────────────┐
    │ implementation   │  Available at compile + runtime
    │ (main code)      │  Most common
    └──────────────────┘
    
    ┌──────────────────┐
    │ api              │  Expose to other modules
    │                  │  Use for public API
    └──────────────────┘
    
    ┌──────────────────┐
    │ compileOnly      │  Only at compile time
    │                  │  Example: lombok
    └──────────────────┘
    
    ┌──────────────────┐
    │ runtimeOnly      │  Only at runtime
    │                  │  Example: PostgreSQL driver
    └──────────────────┘
    
    ┌──────────────────┐
    │ testImplementation│ Only for tests
    │                  │  Example: JUnit, Mockito
    └──────────────────┘
```

### Ví dụ thực tế:

```kotlin
dependencies {
    // Spring Boot — cần cả compile và runtime
    implementation("org.springframework.boot:spring-boot-starter-web")
    
    // Lombok — chỉ cần lúc compile (generate code)
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    
    // PostgreSQL driver — chỉ cần lúc runtime
    // (code dùng JPA abstraction, không reference trực tiếp)
    runtimeOnly("org.postgresql:postgresql")
    
    // JUnit — chỉ cần cho test
    testImplementation("org.junit.jupiter:junit-jupiter")
}
```

---

## 5. Gradle Tasks

### 5.1 Built-in tasks

```bash
# Xem tất cả tasks
./gradlew tasks

# Build toàn bộ (compile + test + package)
./gradlew build

# Chỉ compile
./gradlew compileJava

# Chạy test
./gradlew test

# Skip test
./gradlew build -x test

# Clean + build
./gradlew clean build

# Chạy Spring Boot app
./gradlew :api-service:bootRun

# Chạy với profile
./gradlew :api-service:bootRun --args='--spring.profiles.active=kafka'
```

### 5.2 Task dependencies

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    TASK EXECUTION ORDER                                │
└─────────────────────────────────────────────────────────────────────────┘

    build
      │
      ├── assemble
      │     │
      │     └── jar
      │           │
      │           └── classes
      │                 │
      │                 ├── compileJava  ← Biên dịch .java → .class
      │                 └── processResources  ← Copy resources
      │
      └── check
            │
            └── test  ← Chạy unit tests

    Gradle tự động:
    1. Tính dependency graph
    2. Execute tasks theo đúng thứ tự
    3. Skip task nếu input không thay đổi (UP-TO-DATE)
```

### 5.3 Custom task

```kotlin
// build.gradle.kts
tasks.register("hello") {
    doLast {
        println("Hello from custom task!")
    }
}

// Chạy: ./gradlew hello
```

---

## 6. Gradle Wrapper

### 6.1 Wrapper là gì?

**Gradle Wrapper = script để chạy Gradle mà không cần cài Gradle.**

```bash
# Không cần cài Gradle, chỉ cần:
./gradlew build       # Linux/Mac
.\gradlew.bat build   # Windows

# Wrapper tự:
# 1. Tải Gradle version đúng (từ gradle-wrapper.properties)
# 2. Chạy command
```

### 6.2 gradle-wrapper.properties

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

**Quan trọng:** Commit gradle-wrapper vào git để teammate chạy cùng version.

---

## 7. Debugging Build

### 7.1 Build failed

```bash
# Xem chi tiết lỗi
./gradlew build --stacktrace

# Xem log chi tiết hơn
./gradlew build --info

# Debug cực chi tiết
./gradlew build --debug

# Xem tại sao task chạy (hay UP-TO-DATE)
./gradlew build --info | grep "Task :compileJava"
```

### 7.2 Dependency issues

```bash
# Xem dependency tree
./gradlew :api-service:dependencies

# Xem dependency của 1 scope
./gradlew :api-service:dependencies --configuration implementation

# Kiểm tra conflict
./gradlew :api-service:dependencyInsight --dependency spring-core
```

**Ví dụ output:**

```
+--- org.springframework.boot:spring-boot-starter-web:3.3.0
|    +--- org.springframework.boot:spring-boot-starter:3.3.0
|    |    +--- org.springframework.boot:spring-boot:3.3.0
|    |    |    \--- org.springframework:spring-core:6.1.8
|    |    \--- ...
|    +--- org.springframework:spring-web:6.1.8
|    \--- org.springframework:spring-webmvc:6.1.8
```

### 7.3 Clean cache

```bash
# Clean build output
./gradlew clean

# Refresh dependencies (re-download)
./gradlew build --refresh-dependencies

# Xóa Gradle cache (nuclear option)
rm -rf ~/.gradle/caches  # Linux/Mac
rmdir /s %USERPROFILE%\.gradle\caches  # Windows
```

---

## 8. Common Errors

### 8.1 `Could not find org.example:foo:1.0`

**Nguyên nhân:** Dependency không tồn tại hoặc sai repo.

**Fix:**
```kotlin
repositories {
    mavenCentral()
    // Thêm custom repo nếu cần
    maven("https://jitpack.io")
}
```

### 8.2 `Unsupported class file major version X`

**Nguyên nhân:** Version Java compile khác version Java runtime.

**Fix:**
```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)  // Fix version
    }
}
```

### 8.3 `OutOfMemoryError` khi build

**Fix trong `gradle.properties`:**
```properties
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
```

### 8.4 Task UP-TO-DATE nhưng vẫn muốn chạy

```bash
./gradlew build --rerun-tasks
```

---

## 9. Trong dự án này

### 9.1 Lệnh thường dùng

```bash
cd source

# Build (skip test cho nhanh)
./gradlew build -x test

# Chạy Variant A
./gradlew :api-service:bootRun

# Chạy Variant C (Kafka)
./gradlew :api-service:bootRun --args='--spring.profiles.active=kafka'

# Chạy benchmark
./gradlew :bench:gatlingRun-simulations.UniformSimulation
```

### 9.2 Debug một module cụ thể

```bash
# Xem dependencies của api-service
./gradlew :api-service:dependencies

# Build chỉ module common
./gradlew :common:build

# Clean một module
./gradlew :api-service:clean
```

---

## 10. Kiểm tra hiểu bài

1. Sự khác nhau giữa `implementation` và `runtimeOnly`?
2. Tại sao cần Gradle Wrapper?
3. Task nào chạy trước: `compileJava` hay `jar`?
4. `./gradlew build -x test` làm gì?
5. Khi gặp `ClassNotFoundException` lúc runtime, đâu là nơi đầu tiên để check?

### Đáp án

1. `implementation`: cần cả compile và runtime. `runtimeOnly`: chỉ cần runtime (vd: DB driver)
2. Để mọi máy chạy cùng Gradle version, không cần cài Gradle thủ công
3. `compileJava` chạy trước (jar cần .class files)
4. Build nhưng skip test (tiết kiệm thời gian khi dev)
5. Check `build.gradle.kts` xem có thiếu dependency không; chạy `./gradlew :module:dependencies`

---

## 📚 Tiếp theo

→ [`02-spring-boot-startup.md`](./02-spring-boot-startup.md) — Spring Boot khởi động như thế nào
