# Services uber-JAR design (internal)

Pointer file so `{@code ALTASTATA_SERVICES_UBER_DESIGN.md}` JavaDoc
references resolve when browsing this module.

One JVM starts at `AltaStataServicesLauncher`, which delegates to the
`AltaStataServicesApplication` Micronaut bootstrap. It hosts gRPC, the S3
gateway, Py4J (port 25333, `altastata.services.py4j.enabled`), and—when a UI
directory is configured—the Web Console, sharing `AccountRegistry`. The full
internal specification is not published with this source tree.
