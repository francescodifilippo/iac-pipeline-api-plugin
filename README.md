# IaC Pipeline API

**Plugin ID:** `iac-pipeline-api`  
**Status:** pre-release

Shared Jenkins Pipeline API for remote infrastructure-as-code execution. It provides the durable, provider-neutral lifecycle used by the OTF, Terrakube, and OCI Resource Manager provider plugins.

## Requirements

- Jenkins 2.568.3 or newer
- Java 21
- Maven 3.9.6+ for development

## Provider family

- [OTF Pipeline](https://github.com/francescodifilippo/otf-pipeline-plugin)
- [Terrakube Pipeline](https://github.com/francescodifilippo/terrakube-pipeline-plugin)
- [OCI Resource Manager Pipeline](https://github.com/francescodifilippo/oci-resource-manager-pipeline-plugin)

Provider plugins expose user-facing Pipeline syntax. This core plugin intentionally does not provide a generic end-user provisioning step.

## Architecture

`IacBackend` is the provider extension point.

A submission receives a provider-neutral `SubmissionRequest` containing:

- `connectionId`
- opaque `targetId`
- durable `requestToken`
- non-secret provider parameters

Jenkins persists a build-scoped `RemoteOperation` containing the provider, connection, target, remote ID, status, request token, and provider-selected non-secret metadata.

The core also provides:

- asynchronous polling without holding a Jenkins executor;
- `waitForCompletion: false` plus later await semantics;
- build-scoped operation correlation through `operationKey`;
- timeout handling;
- restart-safe state persistence;
- optional idempotent resubmission for providers that can honor the persisted request token.

## Restart and durability

The remote identity is stored on the Jenkins build. After a controller restart, polling can continue from the persisted `RemoteOperation`.

If Jenkins restarts after submission started but before a remote ID was saved:

- providers that implement `supportsIdempotentSubmit()` may safely resubmit with the persisted request token;
- other providers fail closed and require provider-side reconciliation rather than risking a duplicate destructive request.

## Security

Credentials must never be stored in `SubmissionRequest.parameters`, `RemoteOperation.metadata`, logs, or Pipeline source. Provider plugins are responsible for resolving credentials from Jenkins Credentials at execution time.

Suspected vulnerabilities should follow [SECURITY.md](SECURITY.md).

## Compatibility

The current API is pre-release and may still evolve before the first published Jenkins release. Once the first public release is made, changes to provider-facing contracts should follow normal compatibility and deprecation practices.

## Development

```bash
mvn -B -ntp verify
mvn -B -ntp install
```

Provider repositories can then build against the locally installed `0.1.0-SNAPSHOT`.

The test suite includes Jenkins test-harness coverage for persisted operation state across controller restarts.

## CI and Jenkins hosting

GitHub Actions validates pull requests with Java 21. A root `Jenkinsfile` is also present for future use by `ci.jenkins.io` after the repository is accepted into the `jenkinsci` organization.

Before publishing provider plugins, release this core first and replace provider SNAPSHOT dependencies with the released core version.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT License. See [LICENSE](LICENSE).
