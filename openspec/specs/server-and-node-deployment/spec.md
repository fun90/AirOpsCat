## Purpose
Define server inventory, node lifecycle, deployment orchestration, deployment-state semantics, and remote core management behavior.

## Requirements

### Requirement: Administrators SHALL manage server inventory and connectivity
The system SHALL provide admin APIs for server lifecycle management, server configuration preview, supplier and auth-type metadata, SSH connectivity testing, enablement toggles, renewal, and traffic calibration.

#### Scenario: Server connectivity can be validated before operations
- **WHEN** an administrator invokes the server connection test endpoint
- **THEN** the system validates the configured connection details and returns the test result

#### Scenario: Server lifecycle actions are available
- **WHEN** an administrator manages a server through the admin API
- **THEN** the system supports create, update, enable, disable, renew, and related operational actions

### Requirement: Administrators SHALL manage nodes, route rules, and tags used for deployment authorization
The system SHALL provide APIs for node lifecycle management, available-port checks, default inbound generation, node copy, route-rule lifecycle actions, and tag-to-account or tag-to-node authorization relationships. Node lifecycle APIs SHALL preserve deployment-state semantics when node changes affect remote configuration.

#### Scenario: Node configuration can be prepared before deployment
- **WHEN** an administrator requests node metadata such as available ports, core types, protocols, or default inbound data
- **THEN** the system returns the information needed to configure the node

#### Scenario: Node changes require deployment refresh
- **WHEN** an administrator creates a node, edits deployment-affecting node fields, toggles enablement, changes node tag bindings, or restores a deployment version
- **THEN** the system marks the affected node as pending deployment

#### Scenario: Tag relationships can authorize accounts and nodes
- **WHEN** an administrator manages tag bindings for accounts or nodes
- **THEN** the system persists and exposes those authorization relationships for later subscription and routing use

### Requirement: Node deployments SHALL support execution, batching, version history, restore, and core switching
The system SHALL orchestrate node deployment workflows, including standard deploy, forced deploy, batch deploy, core switching, deployment version listing, and version restore. Node deployment state SHALL distinguish pending deployment, pending deletion, and deployed nodes.

#### Scenario: Single node deployment can be executed
- **WHEN** an administrator invokes deployment for a node whose deployment state is pending deployment
- **THEN** the system prepares deployment data, executes the deployment workflow, marks the node as deployed after success, and records deployment output or history

#### Scenario: Pending deletion node is removed after deployment refresh
- **GIVEN** a node has already been deployed to a server
- **WHEN** an administrator deletes the node
- **THEN** the system marks the node as pending deletion instead of immediately deleting it
- **AND WHEN** deployment for the affected server succeeds
- **THEN** the system excludes the node from the generated remote configuration and physically removes the node record after the remote configuration has been refreshed

#### Scenario: Historical deployment version can be restored
- **WHEN** an administrator restores a recorded deployment version for a node
- **THEN** the system reapplies the selected version to the node data and marks the node as pending deployment until deployment succeeds

### Requirement: Remote installation and core management SHALL operate through server-side execution abstractions
The system SHALL support one-click install script discovery, preview, execution, and protocol-specific core management through SSH-backed remote operations.

#### Scenario: Install script can be previewed before execution
- **WHEN** an administrator requests preview for an install script
- **THEN** the system returns the script content or prepared execution content without running it

#### Scenario: Core-specific runtime actions can be delegated
- **WHEN** the system is asked to start, stop, restart, inspect, or switch a supported core implementation
- **THEN** it routes the request through the matching core-management strategy for that node or server context
