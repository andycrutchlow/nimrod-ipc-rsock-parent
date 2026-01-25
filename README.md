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

> ⚠️ Nimrod is **not** a Spring Boot starter and does **not** auto-configure itself.
> Spring Boot is required only to support `@ConfigurationProperties`.

### Runtime stack (managed via BOM)
- Reactor
- RSocket
- Netty
- Jackson (explicit, not implicit)
- Jakarta Annotations

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
      <version>3.5.4</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>


### History
Nimrod Inter-Process Communication Mk 2:
This is a re-implementation of Nimrod IPC, originally based on ZeroMQ, now rebuilt using Spring Boot, RSocket, and Reactive Streams.

This new version offers several advantages — primarily that it's now a Java-only solution, making it easier to deploy in environments already using the Spring/Spring Boot framework.

One of the motivations for this rewrite was message loss under extreme load in the ZeroMQ-based version. This issue now appears to be resolved.

Most of the original API behavior and functionality have been preserved. However, pub/sub in a many-to-one publishing pattern is no longer supported directly. That said, a simple and effective alternative has been provided.

In the RemoteServerService implementation, there is a fireAndForget operation that performs a non-blocking, asynchronous send on the RSocket client. From the caller’s perspective, this is equivalent to multiple publishers sending messages to a single receiver.

On the receiving side (the Server), the corresponding @MessageMapping-annotated method returns either void or Mono<Void>, maintaining the expected reactive semantics.

More documentation to follow.
