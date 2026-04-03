## 1. Capability Audit

- [ ] 1.1 Verify the five proposed capability boundaries against the current controllers, services, scheduler, and templates so the baseline scope matches the implemented product.
- [ ] 1.2 Confirm that no existing spec in `openspec/specs/` overlaps with the new baseline capability names before applying the change.

## 2. Baseline Spec Review

- [ ] 2.1 Review `access-and-console-security` and `account-and-subscription-management` requirements against the current authentication, user, account, traffic, transaction, and subscription flows.
- [ ] 2.2 Review `server-and-node-deployment` requirements against the current server, node, route-rule, tag, deployment, install, SSH, and core-management flows.
- [ ] 2.3 Review `domain-and-dns-management` and `platform-operations-and-notifications` requirements against the current domain, DNS, backup, monitoring, scheduler, and notification flows.

## 3. Adopt the Baseline

- [ ] 3.1 Apply this change so the baseline capability specs become the repository source of truth under `openspec/specs/`.
- [ ] 3.2 Archive the applied change and use the new capability specs as the target for future OpenSpec deltas.
