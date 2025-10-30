# nimrod-ipc-rsock
Nimrod Inter-Process Communication Mk 2:
This is a re-implementation of Nimrod IPC, originally based on ZeroMQ, now rebuilt using Spring Boot, RSocket, and Reactive Streams.

This new version offers several advantages — primarily that it's now a Java-only solution, making it easier to deploy in environments already using the Spring/Spring Boot framework.

One of the motivations for this rewrite was message loss under extreme load in the ZeroMQ-based version. This issue now appears to be resolved.

Most of the original API behavior and functionality have been preserved. However, pub/sub in a many-to-one publishing pattern is no longer supported directly. That said, a simple and effective alternative has been provided.

In the RemoteServerService implementation, there is a fireAndForget operation that performs a non-blocking, asynchronous send on the RSocket client. From the caller’s perspective, this is equivalent to multiple publishers sending messages to a single receiver.

On the receiving side (the Server), the corresponding @MessageMapping-annotated method returns either void or Mono<Void>, maintaining the expected reactive semantics.

More documentation to follow.
