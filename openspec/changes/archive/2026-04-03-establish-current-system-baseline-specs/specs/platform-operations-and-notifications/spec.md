## ADDED Requirements

### Requirement: System configuration SHALL expose grouped operational settings and task controls
The system SHALL provide grouped system-configuration APIs, including task listings and manual task run, pause, and resume controls for supported operational jobs.

#### Scenario: Operator reviews configured task controls
- **WHEN** an administrator requests `/api/admin/system-configs/tasks`
- **THEN** the system returns the available managed tasks and their control metadata

#### Scenario: Operator manually runs or pauses a managed task
- **WHEN** an administrator invokes the run, pause, or resume action for a supported task key
- **THEN** the system updates task execution state accordingly

### Requirement: Scheduled operational jobs SHALL be registered from configuration-backed cron definitions
The system SHALL programmatically register platform jobs for backup, backup cleanup, monitor cleanup, monitor alerts, traffic-threshold notification, account expiration handling, expiration notification, traffic-stat collection, and core-config cleanup using configured cron values.

#### Scenario: Scheduler registers configured jobs at startup
- **WHEN** the application initializes its programmatic task manager
- **THEN** the system registers each supported operational job using its configured cron expression

#### Scenario: Cron-backed jobs remain centrally configurable
- **WHEN** an operator updates a cron-backed system configuration value
- **THEN** the system uses that configuration as the scheduling source for the corresponding managed task

### Requirement: Backup operations SHALL support manual execution and file-based administration
The system SHALL provide admin APIs to run backups, upload backup files, download backup files, restore from a selected backup, and delete backup files.

#### Scenario: Manual backup can be started
- **WHEN** an administrator requests the backup run endpoint
- **THEN** the system executes the backup workflow and returns the operation result

#### Scenario: Selected backup can be restored
- **WHEN** an administrator requests restore for a stored backup file
- **THEN** the system executes the restore workflow using that file

### Requirement: Monitoring and traffic collection SHALL support operational visibility and thresholds
The system SHALL collect and expose server monitoring summaries, charts, records, traffic calibration actions, account traffic statistics, and server traffic threshold notifications.

#### Scenario: Server monitoring data can be queried
- **WHEN** an administrator requests monitor summary, charts, or records for a server
- **THEN** the system returns the stored monitoring information for that server

#### Scenario: Traffic thresholds can trigger operational handling
- **WHEN** scheduled or manual traffic-threshold checks detect a threshold condition
- **THEN** the system emits the corresponding notification workflow

### Requirement: Notification integrations SHALL support Bark-based delivery and update or expiration signaling
The system SHALL provide Bark notification administration and use notification services for expiration, monitor alerts, traffic thresholds, and release update awareness.

#### Scenario: Bark integration can be tested manually
- **WHEN** an administrator invokes a Bark test or notify endpoint
- **THEN** the system attempts message delivery and returns the result

#### Scenario: Operational notifications can be emitted from scheduled workflows
- **WHEN** expiration, monitoring, traffic, or update workflows detect a notifiable condition
- **THEN** the system uses the configured notification path to send the relevant alert
