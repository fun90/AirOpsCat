## ADDED Requirements

### Requirement: Administrators SHALL choose how batch node tag updates are applied
The system SHALL provide a mode selector for batch node tag adjustment so administrators can explicitly choose whether the selected tags replace the current tag set or are appended to it.

#### Scenario: Replace mode is available in the batch tag modal
- **WHEN** an administrator opens the node batch tag adjustment modal
- **THEN** the system shows a replace option that describes overwriting each selected node's current tags with the checked tags

#### Scenario: Append mode is available in the batch tag modal
- **WHEN** an administrator opens the node batch tag adjustment modal
- **THEN** the system shows an append option that describes preserving current tags and adding the checked tags

### Requirement: Batch node tag updates SHALL default to replace semantics for compatibility
The batch node tag update API SHALL treat requests with no explicit mode as replace operations so existing callers continue to behave as they do today.

#### Scenario: Legacy request omits the mode field
- **WHEN** the batch node tag update endpoint receives `nodeIds` and `tagIds` without a mode value
- **THEN** the system replaces each selected node's tag set with the submitted tags

### Requirement: Replace and append modes SHALL compute effective tag changes correctly
The system SHALL compute the target tag set for each selected node according to the chosen mode and only mark nodes as changed when their effective tag set differs from the current one.

#### Scenario: Replace mode overwrites the current tag set
- **WHEN** the administrator submits batch tag adjustment in replace mode
- **THEN** each selected node's target tag set is exactly the checked tags

#### Scenario: Append mode preserves current tags
- **WHEN** the administrator submits batch tag adjustment in append mode
- **THEN** each selected node keeps its current tags and gains any checked tags that were not already present

#### Scenario: Unchanged nodes remain unchanged in append mode
- **WHEN** a selected node already contains all tags checked for append mode
- **THEN** the system leaves that node's tag set and deployment state unchanged and counts it as unchanged in the batch result

### Requirement: Effective batch tag changes SHALL reset deployment status
The system SHALL mark only the nodes whose effective tag set changed during batch tag adjustment as undeployed so later deployment workflows can reapply tag-dependent configuration.

#### Scenario: Changed node becomes undeployed
- **WHEN** a node's effective tag set changes through replace or append mode
- **THEN** the system sets that node's deployment flag to not deployed before returning success

#### Scenario: Unchanged node keeps its deployment state
- **WHEN** a node's effective tag set is identical before and after the batch request
- **THEN** the system does not alter that node's deployment flag
