# Nimrod IPC (RSocket)

**Nimrod IPC** is a low-latency, point-to-point inter-process communication (IPC) library for JVM applications, built on **RSocket**, **Reactor**, and **Spring Framework**.

It is designed for systems that need:
- predictable latency
- explicit control over concurrency and backpressure
- efficient binary serialization (Kryo)
- strongly typed, interface-driven messaging

Nimrod IPC is used in production trading systems but is published as a general-purpose library.

---

## Design goals

- **Low latency**  
  Uses RSocket over TCP or WebSocket with Netty transport. Potentially over aeron

- **Explicit concurrency**  
  No broker, no fan-out magic. You control threading, queues, and execution models.

- **Efficient serialization**  
  Kryo is used for high-performance binary encoding.

- **Strong typing**  
  Interface-driven RPC style with compile-time code generation.

- **Spring-integrated, not Spring-Boot-dependent**  
  Nimrod integrates with Spring for lifecycle and configuration, without being a Spring Boot application or starter.

---

## What Nimrod IPC is *not*

- ❌ Not a message broker
- ❌ Not a replacement for Kafka / RabbitMQ
- ❌ Not HTTP-based
- ❌ Not opinionated about REST, controllers, or web stacks

It is a **direct, point-to-point IPC layer**.

---

## Requirements

### Java
- **Java 21+**

### Spring
- **Spring Framework 6.1+**
- **Spring Boot 3.5+** (required for configuration binding only)

Nimrod-IPC-RSock is designed to be used within **Spring / Spring Boot applications**.

The library relies on Spring for:
- application lifecycle management
- dependency injection
- configuration via `application.yaml` / `application.properties`
- automatic registration of generated RMI controllers and client proxies

As a result, Nimrod-IPC-RSock is **not a standalone networking library** and does not provide a raw, framework-agnostic API.  
It is intended to be embedded naturally into existing Spring-based JVM services.

---

## Modules


| Module | Description |
|------|------------|
| `nimrod-ipc-rsock` | Core IPC runtime built on RSocket, Reactor, and Spring |
| `nimrod-ipc-rsock-annotations` | Public annotations for defining IPC interfaces and services |
| `nimrod-ipc-rsock-processor` | Annotation processor that generates routing and dispatch code at compile time |


### `nimrod-ipc-rsock`
Core runtime:
- RSocket client & server
- Kryo serialization
- Connection pooling
- Subscriber execution models
- Spring integration

### `nimrod-ipc-rsock-annotations`
Public annotations used by application code:
- `@NimrodRmiInterface`
- `@NimrodRmiService`
- `@NimrodRequestResponse`
- `@NimrodFireAndForget`

### `nimrod-ipc-rsock-processor`
Compile-time annotation processor that generates:
- RSocket routing metadata
- Client stubs
- Server dispatch glue

---

## Dependency management

Nimrod IPC uses **Spring Boot’s dependency BOM** for version alignment, but **does not depend on Boot starters**.

### Recommended setup (consumer project)

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>3.5.4</version> <!-- or greater -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

---

### Additional documentation will follow covering the existing pub/sub (one-to-many and many-to-one) messaging support.

## Examples

Runnable examples are available in the
[nimrod-ipc-rsock-samples](https://github.com/andycrutchlow/nimrod-ipc-rsock-samples) repository.
