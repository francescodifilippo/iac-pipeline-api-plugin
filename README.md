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
- opaque `targetId`, always the resolved provider ID (see [Target names](#target-names))
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

## Operation keys

Each provision stage records its remote operation in the build under an `operationKey`.

- If `operationKey` is not set, the default key is derived from the provider, the target, and the stage's provision parameters (for example `mode`). A plan stage and an apply stage on the same target therefore get different keys and both submit.
- A provision stage never adopts an operation that another provision stage of the same build recorded. Reusing a key fails the stage with a message asking for a distinct `operationKey` in the stage options. This also applies when a provision stage runs again in the same build (for example inside `retry`): give each attempt its own key.
- Set `operationKey` explicitly whenever a later stage refers to the operation, for example an await stage after `waitForCompletion: false`.
- When a stage names its target instead of passing an ID, the default key is derived from the names, not from the resolved ID. A stage naming a target and a stage passing the same target's ID therefore get different keys.

## Target names

Provider plugins may let a provision stage name its target instead of passing a technical ID, for example a workspace name instead of a workspace ID. Existing ID parameters keep working unchanged. When a stage uses names:

- the names are resolved to IDs when the stage runs, with the stage's connection and credentials, before anything is submitted;
- a name must match exactly one remote object. No match, or more than one, fails the stage with a message saying so; the provider never picks one. Resolution failures are not retried, and the stage body does not run;
- the build log shows the resolved ID, for example `IaC otf: resolved target to ws-abc123`;
- everything after resolution uses the resolved IDs: the stored operation, provider checks such as "the plan belongs to the same stack", status polling, and await stages.

Await stages refer to an operation by `operationKey` and never resolve names.

## Status polling

A failed status poll that the provider classifies as transient (`IacBackend.isTransient`) is logged and retried at the next polling interval, up to 5 consecutive times. A successful poll resets the count, and the stage timeout still applies. By default, I/O errors and HTTP 5xx, 408, and 429 responses are transient, and other HTTP 4xx responses fail immediately. Submissions are never retried this way.

## Restart and durability

The remote identity is stored on the Jenkins build. After a controller restart, polling can continue from the persisted `RemoteOperation`.

If Jenkins restarts after submission started but before a remote ID was saved:

- providers that implement `supportsIdempotentSubmit()` may safely resubmit with the persisted request token;
- other providers fail closed and require provider-side reconciliation rather than risking a duplicate destructive request.

## Provider contract for target names

Supporting names in a provider takes a step change and a backend change.

**Step.** Keep the existing ID parameter, make it optional, and add the name parameters:

- `AbstractProvisionStep` accepts a null or blank `targetId` (stored as null, `getTargetId()` returns null). A stage option may omit a `@DataBoundConstructor` argument, but Pipeline Syntax documentation lists constructor arguments as required. The cleaner form is `super(connectionId)` with the ID as an optional setter, which keeps existing `workspaceId: '...'` pipelines working:

  ```java
  @DataBoundConstructor public OtfProvisionStep(String server){super(server);}
  @DataBoundSetter public void setWorkspaceId(String value){setTargetId(value);}
  ```

- Validate "either ID or name" in `parameters()`, which runs when the step starts, and put the names in the returned parameters. With no target ID, every name that identifies the target must be among the parameters: the default `operationKey` is `Identifiers.defaultOperationKey(provider, null, parameters)`, so a missing name would give two stages naming different targets the same key.

**Backend.** Override `IacBackend.resolve`:

```java
ResolvedTarget resolve(String connectionId, String targetId, Map<String,String> parameters, Run<?,?> run) throws Exception
```

- It is called once per provision step, before `operationMetadata`, `store.begin`, and `submit`. It is not called for await steps.
- `targetId` is the step's configured ID, or null when the step uses names. Look the names up with the connection's credentials.
- Require exactly one match. Throw a clear exception for none or several, naming the provider, the name, and the parameter to use instead (for example `use workspaceId`).
- Return `new ResolvedTarget(id, parameters)`. The returned parameters replace the step's parameters; they are not merged. Drop name parameters you no longer need and add resolved IDs (for example an organization ID) that `operationMetadata` and `submit` read.
- With a non-null `targetId` and no names, return the target and parameters unchanged so ID-based pipelines behave as before.
- Lookups must be read-only. The result is persisted with the step execution before the operation is stored, but a controller restart before that point resolves again.
- Parameters, including resolved ones, must not contain secrets. The core logs the resolved target and each parameter value that resolution added or changed.
- The default implementation returns the configured target and parameters, and fails with `<provider> target id is required` when there is no target ID.

**HTTP.** `HttpJsonClient` helps build lookup requests safely:

- `request(method, path, query, body)` appends `query` in iteration order, percent-encoding keys and values (UTF-8, RFC 3986; only letters, digits, and `-._~` are kept). `filter[organization]` and `name==acme` are both safe as given. Query values never appear in exceptions: `IacHttpException` carries only the method and path. `request(method, path, body)` is unchanged.
- The client only encodes; the query syntax is the provider's. For example, Terrakube filters use RSQL, where a value with spaces or reserved characters must be quoted (`name=='my ws'`) and `*` can act as a wildcard. Whatever the filter, compare the returned names exactly before counting matches: a server-side match that is case-insensitive or a wildcard is not an exact match.
- `HttpJsonClient.pathSegment(name)` percent-encodes one path segment, so `/` and other reserved characters cannot change the path: `"/api/v2/organizations/" + pathSegment(org) + "/workspaces/" + pathSegment(name)`. It rejects empty values, `.` and `..`.
- Paths must start with a single `/` and may not contain `?`, `#`, or `.`/`..` segments, whether literal or percent-encoded.

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
