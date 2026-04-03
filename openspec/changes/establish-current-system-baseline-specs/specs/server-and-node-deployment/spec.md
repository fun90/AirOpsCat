## ADDED Requirements

### Requirement: Administrators SHALL manage server inventory and connectivity
The system SHALL provide admin APIs for server lifecycle management, server configuration preview, supplier and auth-type metadata, SSH connectivity testing, enablement toggles, renewal, and traffic calibration.

#### Scenario: Server connectivity can be validated before operations
- **WHEN** an administrator invokes the server connection test endpoint
- **THEN** the system validates the configured connection details and returns the test result

#### Scenario: Server lifecycle actions are available
- **WHEN** an administrator manages a server through the admin API
- **THEN** the system supports create, update, enable, disable, renew, and related operational actions

### Requirement: Administrators SHALL manage nodes, route rules, and tags used for deployment authorization
The system SHALL provide APIs for node lifecycle management, available-port checks, default inbound generation, node copy, route-rule lifecycle actions, and tag-to-account or tag-to-node authorization relationships.

#### Scenario: Node configuration can be prepared before deployment
- **WHEN** an administrator requests node metadata such as available ports, core types, protocols, or default inbound data
- **THEN** the system returns the information needed to configure the node

#### Scenario: Tag relationships can authorize accounts and nodes
- **WHEN** an administrator manages tag bindings for accounts or nodes
- **THEN** the system persists and exposes those authorization relationships for later subscription and routing use

### Requirement: Node deployments SHALL support execution, batching, version history, restore, and core switching
The system SHALL orchestrate node deployment workflows, including standard deploy, forced deploy, batch deploy, core switching, deployment version listing, and version restore.

#### Scenario: Single node deployment can be executed
- **WHEN** an administrator invokes deployment for a node
- **THEN** the system prepares deployment data, executes the deployment workflow, and records deployment output or history

#### Scenario: Historical deployment version can be restored
- **WHEN** an administrator restores a recorded deployment version for a node
- **THEN** the system reapplies the selected version through the deployment workflow

### Requirement: Remote installation and core management SHALL operate through server-side execution abstractions
The system SHALL support one-click install script discovery, preview, execution, and protocol-specific core management through SSH-backed remote operations.

#### Scenario: Install script can be previewed before execution
- **WHEN** an administrator requests preview for an install script
- **THEN** the system returns the script content or prepared execution content without running it

#### Scenario: Core-specific runtime actions can be delegated
- **WHEN** the system is asked to start, stop, restart, inspect, or switch a supported core implementation
- **THEN** it routes the request through the matching core-management strategy for that node or server context
