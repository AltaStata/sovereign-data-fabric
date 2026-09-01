# TLS design (internal)

Pointer file so `{@code TLS_DESIGN.md}` JavaDoc and runtime references
resolve when browsing this module.

The gRPC gateway does not terminate TLS in-process. Production HTTPS is
terminated at a reverse proxy in front of the Services JVM. The full
internal specification is not published with this source tree.
