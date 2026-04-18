# Core Knowledge — Hiểu sâu về Java và dự án

## 🎯 Mục tiêu

Sau khi đọc xong tất cả tài liệu trong thư mục này, bạn sẽ:

- ✅ Hiểu **bản chất** Java hoạt động như thế nào
- ✅ Hiểu **toàn bộ flow** của dự án: từ `main()` đến khi trả response
- ✅ Biết cách **debug và fix** bất kỳ vấn đề nào
- ✅ Tự tin **thêm tính năng mới** hoặc **sửa bug**

---

## 📚 Thứ tự đọc

Đọc theo thứ tự số, mỗi file build trên kiến thức file trước.

| # | File | Nội dung | Thời gian |
|---|------|----------|-----------|
| 00 | [`00-how-java-works.md`](./00-how-java-works.md) | JVM, bytecode, classpath, cách Java chạy | 30 phút |
| 01 | [`01-gradle-build-system.md`](./01-gradle-build-system.md) | Gradle, tasks, dependencies, multi-module | 30 phút |
| 02 | [`02-spring-boot-startup.md`](./02-spring-boot-startup.md) | Spring Boot startup, bean creation | 45 phút |
| 03 | [`03-dependency-injection.md`](./03-dependency-injection.md) | IoC, @Autowired, @Bean, scope | 30 phút |
| 04 | [`04-project-structure.md`](./04-project-structure.md) | Walkthrough dự án: từng file làm gì | 45 phút |
| 05 | [`05-request-lifecycle.md`](./05-request-lifecycle.md) | HTTP request flow từ A-Z | 30 phút |
| 06 | [`06-jpa-hibernate.md`](./06-jpa-hibernate.md) | JPA, transaction, connection pool | 45 phút |
| 07 | [`07-threading-model.md`](./07-threading-model.md) | Thread, ExecutorService, async | 30 phút |
| 08 | [`08-debugging-guide.md`](./08-debugging-guide.md) | Cách debug, breakpoint, stack trace | 30 phút |
| 09 | [`09-common-errors.md`](./09-common-errors.md) | Lỗi thường gặp và cách fix | 30 phút |
| 10 | [`10-logging-observability.md`](./10-logging-observability.md) | Logging, metrics, tracing | 20 phút |

**Tổng:** ~6 giờ đọc hiểu.

---

## 🚀 Quick Start nếu bạn đang gấp

Nếu chỉ có 1 giờ, đọc 3 file này:

1. **`04-project-structure.md`** — Hiểu dự án có gì
2. **`05-request-lifecycle.md`** — Hiểu flow xử lý request
3. **`08-debugging-guide.md`** — Biết cách debug khi có lỗi

---

## 🧭 Bản đồ kiến thức

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CORE KNOWLEDGE MAP                              │
└─────────────────────────────────────────────────────────────────────────┘

                         ┌──────────────────┐
                         │  00. How Java    │
                         │     Works        │
                         │  (JVM, bytecode) │
                         └────────┬─────────┘
                                  │
                 ┌────────────────┼────────────────┐
                 │                │                │
                 ▼                ▼                ▼
       ┌─────────────────┐ ┌─────────────┐ ┌─────────────┐
       │ 01. Gradle      │ │ 02. Spring  │ │ 07. Thread  │
       │     Build       │ │    Boot     │ │    Model    │
       └────────┬────────┘ └──────┬──────┘ └─────────────┘
                │                 │
                │                 ▼
                │         ┌──────────────┐
                │         │ 03. DI & IoC │
                │         └──────┬───────┘
                │                │
                └────────────────┼───────────────────┐
                                 ▼                   ▼
                         ┌──────────────┐    ┌─────────────┐
                         │ 04. Project  │    │ 06. JPA &   │
                         │  Structure   │    │  Hibernate  │
                         └──────┬───────┘    └─────────────┘
                                │
                                ▼
                         ┌──────────────┐
                         │ 05. Request  │
                         │  Lifecycle   │
                         └──────┬───────┘
                                │
                 ┌──────────────┼──────────────┐
                 ▼              ▼              ▼
         ┌─────────────┐ ┌────────────┐ ┌──────────────┐
         │ 08. Debug   │ │ 09. Errors │ │ 10. Logging  │
         └─────────────┘ └────────────┘ └──────────────┘
```

---

## 💡 Triết lý học

1. **Hiểu WHY trước HOW** — Đừng copy-paste code, hiểu tại sao code vậy
2. **Thử và sai** — Break code để hiểu khi nào nó không work
3. **Đọc stack trace** — 90% bug có thể fix từ stack trace
4. **Debug step-by-step** — Dùng IDE debugger, không chỉ `println`
5. **Đọc source code** — Spring, JPA đều open source, đọc để hiểu sâu
