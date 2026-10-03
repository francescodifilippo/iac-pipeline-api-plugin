# IaC Pipeline API

**Proposed Jenkins plugin ID:** `iac-pipeline-api`  
**Proposed GitHub repository:** `iac-pipeline-api-plugin`  
**Version:** `0.1.0-SNAPSHOT` (source prototype)

Shared internal API for two independent Jenkins plugins:
- `otf-pipeline` — OTF Declarative Pipeline support
- `terrakube-pipeline` — Terrakube Declarative Pipeline support

Includes connection model, credentials handling, HTTP client, backend extension points, build-scoped remote-operation IDs, asynchronous polling and stage-wrapper implementation. Both provider plugins have a **required Maven/HPI dependency** on this plugin.

## Build

Java 21, Maven 3.9.6+:

```bash
mvn -B -ntp verify
mvn -B -ntp install   # before testing provider plugins against the local SNAPSHOT
```

## Release order

Release this plugin first; change each provider from `0.1.0-SNAPSHOT` to the exact released core version before publishing the provider. Do not publish a provider until the declared core version is available from the Jenkins plugin repository.

## Current limitations

The split is complete at source level, **not** a verified plugin release. Real Jenkins test builds, restart tests and real server API tests remain necessary. The old prototype's asynchronous behavior and Declarative wrapper syntax must pass integration tests before they can be advertised as production-ready.
