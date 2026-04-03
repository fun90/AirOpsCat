## Why

AirOpsCat already contains a broad set of production features, but its current behavior is only implicit in controllers, services, templates, and configuration. Establishing an OpenSpec baseline now will make future changes safer by turning the existing system boundaries, roles, and operational workflows into explicit contracts before more features are added.

## What Changes

- Analyze the current Quarkus application structure and capture the main business capabilities already implemented in code.
- Define baseline OpenSpec capabilities for authentication and console access, account and subscription management, server and node deployment management, domain and DNS management, and platform operations.
- Document the main user-facing flows, role boundaries, background jobs, and external integrations that future changes must preserve or extend.
- Create an implementation-ready OpenSpec change package with proposal, design, tasks, and capability specs that can serve as the starting point for later deltas.

## Capabilities

### New Capabilities
- `access-and-console-security`: Covers form login, role-based access control, protected/public routes, and console entry behavior.
- `account-and-subscription-management`: Covers user, account, traffic, transaction, and subscription lifecycle behavior exposed by the current application.
- `server-and-node-deployment`: Covers server, node, route rule, tag, deployment, install, SSH, and core-management workflows.
- `domain-and-dns-management`: Covers domain records, DNS provider configuration, and domain-to-DNS synchronization behaviors.
- `platform-operations-and-notifications`: Covers scheduled jobs, traffic collection, backup, monitoring, expiration checks, update checks, and Bark notifications.

### Modified Capabilities

None.

## Impact

- Affected code spans `controller/`, `service/`, `repository/`, `security/`, `model/`, `config/`, and `src/main/resources/templates/`.
- The resulting specs will describe behavior already implemented across admin APIs, public subscription endpoints, scheduled jobs, SSH-based remote operations, MySQL persistence, and Qute-based console pages.
- Future feature work will be able to target explicit capability specs instead of rediscovering behavior from source code each time.
