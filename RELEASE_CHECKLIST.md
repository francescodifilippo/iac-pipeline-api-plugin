# Release checklist — IaC Pipeline API

Status: **pre-release**.

## Repository readiness

- [x] MIT license declared in POM and repository
- [x] Jenkins-compatible artifact ID and `io.jenkins.plugins` group ID
- [x] project URL, SCM metadata, and GitHub issue tracker declared in POM
- [x] Java 21 GitHub Actions build
- [x] root `Jenkinsfile` for future `ci.jenkins.io`
- [x] Dependabot, CODEOWNERS, PR template, CONTRIBUTING and security guidance
- [x] documentation-as-code README
- [x] Jenkins test-harness controller restart coverage for persisted operation state

## Before Jenkins hosting / first release

- [x] Complete resolved dependency-tree and bundled-license audit: the HPI bundles no third-party library, only Jenkins plugin dependencies
- [ ] Run Jenkins Plugin Compatibility Tester / plugin verifier against intended supported versions
- [ ] Add deeper Pipeline restart/abort tests around live `RemoteExecution`
- [ ] Validate timeout, abort and ambiguous-submit race conditions
- [x] Perform an internal HPI installation on the target Jenkins baseline (2.568.3 on 2026-10-30: plugins resolved by the BOM, `agent none` Declarative builds; `otfWorkspace` and `terrakubeWorkspace` against a local stub, `ociRmStack` up to the real OCI endpoint (401 with a dummy key))
- [ ] Open Jenkins plugin hosting request
- [ ] After Jenkins forks the repository, update project/SCM URLs to the canonical `jenkinsci` repository
- [ ] Obtain Jenkins repository release permissions
- [ ] Publish a versioned `iac-pipeline-api` release before publishing provider plugins
- [ ] Publish release notes

Official hosting guide: https://www.jenkins.io/doc/developer/publishing/requesting-hosting/
