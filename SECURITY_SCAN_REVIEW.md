# Jenkins Security Scan triage

Reviewed: 2026-10-08. Scanner: Jenkins-specific CodeQL pack used by Jenkins Security Scan.

This is a maintainer review of findings, not a certification or an instruction to suppress future alerts. Keep the generated SARIF reports and review new findings on each change.

## `jenkins/plaintext-storage`

The initial scan produced seven possible plaintext-storage findings:

| Source | Field | Maintainer assessment |
| --- | --- | --- |
| `AbstractAwaitStep` | `operationKey` | Non-secret build-local correlation key; intentionally serialized as a Pipeline step argument. |
| `AbstractProvisionStep` | `operationKey` | Non-secret operation identifier; intentionally part of Pipeline configuration. |
| `RemoteExecution` | `key` | Non-secret operation correlation key; durable state is expected. |
| `RemoteOperation` | `key` | Non-secret operation correlation key; durable state is expected. |
| `RemoteOperation` | `requestToken` | Random UUID idempotency key used to avoid duplicate remote submissions, not an authentication credential. Persistence is intentional. |
| `SubmissionRequest` | `requestToken` | Same non-secret idempotency key; not used to authenticate to the provider. |
| `HttpJsonClient` | `token` | **Real bearer credential**; held in memory by a short-lived, non-Serializable HTTP client used in try-with-resources. It is not part of `RemoteOperation`, `StepExecution`, or build persistence and is not logged by this client. |

## Follow-up

- Do not persist the HTTP bearer token, private keys, or provider authentication secrets in any Pipeline step state or `OperationStoreAction`.
- Keep the HTTP client ephemeral; re-resolve credentials using Jenkins Credentials on each provider operation.
- Add tests if transport lifetimes or persistence boundaries change.
- Do not treat a successful CodeQL workflow as proof that there are zero findings or vulnerabilities.
