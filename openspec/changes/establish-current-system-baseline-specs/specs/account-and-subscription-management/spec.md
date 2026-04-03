## ADDED Requirements

### Requirement: Administrators SHALL manage users and accounts through admin APIs
The system SHALL provide admin APIs for creating, viewing, updating, enabling, and disabling users and accounts, including account views scoped by user and system statistics views.

#### Scenario: Administrator manages an account lifecycle
- **WHEN** an administrator calls the account administration endpoints
- **THEN** the system supports create, read, update, enable, disable, and delete-style lifecycle actions for accounts

#### Scenario: Administrator queries account statistics and ownership
- **WHEN** an administrator requests account stats or accounts by user
- **THEN** the system returns the relevant summary or filtered account list

### Requirement: Account lifecycle operations SHALL include renewal, authorization reset, deployment, and config URL retrieval
The system SHALL support operational account actions beyond CRUD, including renewal, authorization code reset, deployment, config URL generation, and online-account inspection.

#### Scenario: Account authorization can be rotated
- **WHEN** an administrator invokes the reset-auth action for an account
- **THEN** the system generates and persists a new authorization credential for subscription access

#### Scenario: Account subscription artifacts can be requested
- **WHEN** an administrator requests deployment or config URL generation for an account
- **THEN** the system prepares the corresponding deployment or subscription configuration output

### Requirement: Subscription endpoints SHALL generate client-facing configuration from account authorization
The system SHALL expose public subscription endpoints that resolve account authorization codes into client-specific configuration, node listings, and routing rule assets.

#### Scenario: Authorized client config can be downloaded
- **WHEN** a caller requests `/subscribe/config/{authCode}/{osName}/{appName}`
- **THEN** the system returns subscription configuration for the authorized account and target client

#### Scenario: Authorized node list can be requested
- **WHEN** a caller requests `/subscribe/nodes/{authCode}/{appType}`
- **THEN** the system returns the nodes available to that authorized account in the expected client format

### Requirement: Traffic and transaction records SHALL be queryable for operational and business tracking
The system SHALL provide admin APIs for account traffic statistics and transaction records, including aggregate statistics and business-linked transaction lookups.

#### Scenario: Traffic history can be inspected
- **WHEN** an administrator requests account traffic statistics
- **THEN** the system returns stored traffic history and related summary data for the target account

#### Scenario: Transactions can be queried by business context
- **WHEN** an administrator requests transactions by business table and business identifier
- **THEN** the system returns matching transaction records and statistics
