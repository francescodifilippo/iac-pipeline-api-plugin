# IaC Pipeline API

**Proposed Jenkins plugin ID:** `iac-pipeline-api`  
**Proposed GitHub repository:** `iac-pipeline-api-plugin`  
**Version:** `0.1.0-SNAPSHOT` (source prototype)

Shared internal Jenkins API for independent infrastructure-execution providers. Current consumers are:
- `otf-pipeline` — OTF Declarative Pipeline support
- `terrakube-pipeline` — Terrakube Declarative Pipeline support

The core lifecycle is provider-neutral: a provider receives a `SubmissionRequest` with a `connectionId`, opaque `targetId`, persisted `requestToken`, and provider parameters. Jenkins persists a `RemoteOperation` containing the remote ID, normalized status and provider-selected non-secret metadata.

The plugin provides asynchronous polling, build-scoped operation correlation, restart safety, timeout handling and stage-wrapper base classes. It also contains reusable HTTP/token connection helpers for HTTP providers, but `IacBackend` does **not** require that transport model. Providers such as OCI Resource Manager can therefore use their native SDK/configuration model.

Providers may opt in to idempotent resubmission by overriding `supportsIdempotentSubmit()` and honoring the persisted request token. Providers that do not opt in retain fail-safe behavior after an ambiguous restart instead of silently repeating a potentially destructive request.

## Build

Java 21, Maven 3.9.6+:

```bash
mvn -B -ntp verify
mvn -B -ntp install
```

Install the core SNAPSHOT locally before building provider plugins against the same source version.

## Release order

Release this plugin first; change each provider from `0.1.0-SNAPSHOT` to the exact released core version before publishing the provider. Do not publish a provider until the declared core version is available from the Jenkins plugin repository.

## Current limitations

This remains a source prototype, not a verified release. JenkinsRule coverage, controller restart/abort tests and real provider API integration tests are still required before production release.
