# 00. Java hoạt động như thế nào?

## 🎯 Mục tiêu

Sau bài này, bạn hiểu:
- Java code từ `.java` → chạy như thế nào?
- JVM là gì, làm gì?
- Classpath là gì, khi nào bị `ClassNotFoundException`?
- Tại sao Java "viết 1 lần, chạy mọi nơi"?

---

## 1. Big Picture — Java Code chạy như thế nào?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         JAVA EXECUTION FLOW                            │
└─────────────────────────────────────────────────────────────────────────┘

    Bước 1: Write            Bước 2: Compile          Bước 3: Run
    ┌─────────────┐          ┌──────────────┐         ┌─────────────┐
    │  Hello.java │          │ Hello.class  │         │     JVM     │
    │             │  javac   │              │  java   │             │
    │ Source code │────────▶ │  Bytecode    │────────▶│ Execute     │
    │ (text)      │          │  (binary)    │         │ on any OS   │
    └─────────────┘          └──────────────┘         └─────────────┘

    Human-readable          Machine-readable         Running program
```

### 1.1 Ví dụ cụ thể

```java
// File: Hello.java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
```

```bash
# Step 1: Compile .java → .class
javac Hello.java
# Tạo file Hello.class (bytecode)

# Step 2: Run bytecode bằng JVM
java Hello
# Output: Hello, World!
```

---

## 2. JVM — Java Virtual Machine

### 2.1 JVM là gì?

**JVM = máy ảo chạy bytecode.**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           WHY JVM?                                      │
└─────────────────────────────────────────────────────────────────────────┘

    Without JVM (như C++):
    ┌────────────────┐  compile   ┌──────────────────┐
    │  hello.cpp     │──────────▶ │ hello.exe (Win)  │  → Chỉ chạy Win
    │                │──────────▶ │ hello (Linux)    │  → Chỉ chạy Linux
    │                │──────────▶ │ hello (Mac)      │  → Chỉ chạy Mac
    └────────────────┘            └──────────────────┘
    → Phải compile lại cho mỗi OS!

    With JVM (Java):
    ┌────────────────┐  compile   ┌──────────────┐      ┌──────────────┐
    │  Hello.java    │──────────▶ │ Hello.class  │─────▶│ JVM (Win)    │
    │                │            │  (bytecode)  │─────▶│ JVM (Linux)  │
    │                │            │              │─────▶│ JVM (Mac)    │
    └────────────────┘            └──────────────┘      └──────────────┘
    → Compile 1 lần, chạy mọi OS có JVM!
```

### 2.2 JVM Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         JVM INTERNALS                                   │
└─────────────────────────────────────────────────────────────────────────┘

    ┌────────────────────────────────────────────────────────────────┐
    │                          JVM                                   │
    │                                                                │
    │  ┌──────────────┐      ┌────────────────────────────────────┐ │
    │  │ Class Loader │─────▶│          Memory Areas              │ │
    │  │              │      │                                     │ │
    │  │ Load .class  │      │  ┌─────────┐  ┌─────────┐         │ │
    │  │ files into   │      │  │  Heap   │  │  Stack  │         │ │
    │  │ memory       │      │  │(Objects)│  │ (Methods│         │ │
    │  └──────────────┘      │  │         │  │  frames)│         │ │
    │                         │  └─────────┘  └─────────┘         │ │
    │                         │                                     │ │
    │                         │  ┌─────────┐  ┌─────────┐         │ │
    │                         │  │ Metaspace│ │  PC     │         │ │
    │                         │  │ (Classes)│ │ Register│         │ │
    │                         │  └─────────┘  └─────────┘         │ │
    │                         └────────────────────────────────────┘ │
    │                                       │                        │
    │                                       ▼                        │
    │                         ┌────────────────────────────────────┐ │
    │                         │    Execution Engine                │ │
    │                         │                                     │ │
    │                         │  ┌──────────┐  ┌──────────────┐   │ │
    │                         │  │Interpreter│  │ JIT Compiler │   │ │
    │                         │  │(bytecode  │  │ (hot methods │   │ │
    │                         │  │ → machine)│  │  → native)   │   │ │
    │                         │  └──────────┘  └──────────────┘   │ │
    │                         │                                     │ │
    │                         │  ┌──────────────────────────────┐  │ │
    │                         │  │     Garbage Collector        │  │ │
    │                         │  │   (Clean unused objects)     │  │ │
    │                         │  └──────────────────────────────┘  │ │
    │                         └────────────────────────────────────┘ │
    └────────────────────────────────────────────────────────────────┘
```

### 2.3 Memory Areas — Khi nào dùng cái gì?

#### Heap (Bộ nhớ Object)

```java
public class Payment {
    private String paymentId;  // Reference nằm trong heap
    private Long amount;       // Value nằm trong heap
}

// Trong method:
Payment p = new Payment();  
// 'p' là reference trên stack, object Payment nằm trên heap
```

**Đặc điểm Heap:**
- Chứa tất cả **objects** được tạo bằng `new`
- Shared giữa các threads
- Được **Garbage Collector** dọn dẹp
- Size cấu hình bằng `-Xms` (min) và `-Xmx` (max)

#### Stack (Bộ nhớ Method)

```java
public void processPayment() {  // Stack frame tạo mới
    int count = 10;             // Local variable trên stack
    Payment p = new Payment();  // 'p' trên stack, object trên heap
    
    validate(p);  // Stack frame mới cho validate()
    
}  // Stack frame bị pop khi method kết thúc
```

**Đặc điểm Stack:**
- Mỗi thread có stack riêng
- Chứa **method frames**, local variables, parameters
- Auto-cleanup khi method return
- Lỗi `StackOverflowError` khi stack quá sâu (recursion vô hạn)

---

## 3. Classpath — Java tìm Class ở đâu?

### 3.1 Classpath là gì?

**Classpath = danh sách các nơi JVM tìm file `.class` và `.jar`.**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLASSPATH EXAMPLE                               │
└─────────────────────────────────────────────────────────────────────────┘

    java -cp "lib/*;build/classes" com.fraud.api.Main
          └──┬──┘
         classpath
         
    JVM tìm class theo thứ tự:
    1. lib/*.jar          (tất cả JARs trong thư mục lib)
    2. build/classes/     (compiled classes)
    
    Nếu không tìm thấy → ClassNotFoundException
```

### 3.2 Ví dụ thực tế

```bash
# Dự án này
source/
├── api-service/
│   └── build/
│       └── classes/java/main/
│           └── com/fraud/api/
│               └── Application.class  ← Compiled class
└── ~/.gradle/caches/modules-2/files-2.1/
    └── org.springframework.boot/
        └── spring-boot/3.3.0/
            └── spring-boot-3.3.0.jar  ← Dependency JAR
```

Khi chạy app:
```bash
java -cp "api-service/build/classes/java/main:deps/*.jar" com.fraud.api.Application
```

### 3.3 Khi nào bị `ClassNotFoundException`?

```java
// Lỗi thường gặp:
Exception in thread "main" java.lang.ClassNotFoundException: 
    org.springframework.boot.SpringApplication
```

**Nguyên nhân:**
- JAR không có trong classpath
- Dependency version không tương thích
- Quên thêm dependency trong `build.gradle.kts`

**Cách fix:**
```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.3.0")
    //             ^^^^^^^^^^^^^^^^^^^^^^
    //             Thêm dependency này
}
```

---

## 4. Java 21 Features quan trọng

### 4.1 Virtual Threads (Java 21)

```java
// Traditional thread (heavy)
Thread.ofPlatform().start(() -> {
    // Tạo 1 OS thread (tốn ~2MB stack)
});

// Virtual thread (lightweight)
Thread.ofVirtual().start(() -> {
    // Tạo 1 virtual thread (tốn ~KB)
    // Có thể tạo hàng triệu virtual threads
});
```

### 4.2 Records (Immutable DTO)

```java
// Cách cũ (verbose)
public class Payment {
    private final String id;
    private final Long amount;
    
    public Payment(String id, Long amount) {
        this.id = id;
        this.amount = amount;
    }
    
    public String getId() { return id; }
    public Long getAmount() { return amount; }
    // equals, hashCode, toString...
}

// Cách mới (concise)
public record Payment(String id, Long amount) {}
// Auto-generate: constructor, getters, equals, hashCode, toString
```

### 4.3 Pattern Matching

```java
// Cách cũ
if (obj instanceof Payment) {
    Payment p = (Payment) obj;
    System.out.println(p.getId());
}

// Cách mới (Java 21)
if (obj instanceof Payment p) {
    System.out.println(p.getId());
}

// Switch pattern
return switch (decision) {
    case ALLOW -> "Approved";
    case BLOCK -> "Rejected";
    case REVIEW -> "Pending review";
};
```

---

## 5. Lệnh quan trọng

### 5.1 Kiểm tra Java

```bash
# Version
java -version
# Expected: openjdk version "21.x.x"

# Javac version
javac -version
# Expected: javac 21.x.x

# JAVA_HOME
echo $env:JAVA_HOME  # PowerShell
echo %JAVA_HOME%     # CMD
```

### 5.2 Chạy Java file

```bash
# Compile & run (Java 11+)
java Hello.java

# Traditional way
javac Hello.java
java Hello

# With classpath
java -cp "lib/*;classes" com.example.Main

# With heap size
java -Xms512m -Xmx2g -jar app.jar
```

### 5.3 Inspect bytecode

```bash
# Xem bytecode của class file
javap -c Hello.class

# Output:
# Compiled from "Hello.java"
# public class Hello {
#   public static void main(java.lang.String[]);
#     Code:
#        0: getstatic     #2  // Field java/lang/System.out
#        3: ldc           #3  // String Hello, World!
#        5: invokevirtual #4  // Method println
#        8: return
# }
```

---

## 6. Debug tips

### 6.1 Xem JVM flags đang dùng

```bash
# List all JVM flags
java -XX:+PrintFlagsFinal -version | findstr "HeapSize"

# Specific app
jps -v  # List Java processes with their flags
```

### 6.2 Monitor JVM runtime

```bash
# Heap dump
jmap -dump:live,format=b,file=heap.hprof <PID>

# Thread dump
jstack <PID>

# Memory usage
jstat -gc <PID> 1000  # Refresh mỗi 1 giây
```

---

## 7. Kiểm tra hiểu bài

Trả lời các câu hỏi sau (đáp án ở cuối):

1. Tại sao Java có thể "write once, run anywhere"?
2. Sự khác nhau giữa Heap và Stack?
3. `ClassNotFoundException` xảy ra khi nào?
4. JIT Compiler làm gì?
5. Virtual Thread khác gì Platform Thread?

---

### Đáp án

1. **Nhờ JVM** — compile thành bytecode (trung gian), chạy trên bất kỳ JVM nào
2. **Heap:** chứa objects, shared giữa threads, GC dọn dẹp. **Stack:** chứa method frames, mỗi thread có stack riêng
3. Khi JVM không tìm thấy class trong classpath (thiếu JAR, sai classpath)
4. JIT = Just-In-Time, compile bytecode thành native machine code cho các method gọi nhiều lần → tăng tốc
5. **Virtual Thread:** lightweight, managed by JVM, có thể tạo triệu thread. **Platform Thread:** 1-1 với OS thread, tốn ~2MB

---

## 📚 Tiếp theo

→ [`01-gradle-build-system.md`](./01-gradle-build-system.md) — Hiểu về Gradle build system
