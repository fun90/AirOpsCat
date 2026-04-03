## Context

AirOpsCat already exposes `POST /api/admin/nodes/batch-tags` and a corresponding “批量调整节点标签” modal in the node console. The current implementation accepts only `nodeIds` and `tagIds`, and `NodeService.batchUpdateNodeTags` always replaces the node's entire tag set with the selected tags. The modal also warns that the selected tags will completely replace the existing set.

This change crosses DTO, controller, service, and Qute template boundaries, so it benefits from a small design document before implementation. We need to add an explicit mode without breaking existing callers, keep the UI understandable, and make result counting behave consistently across both modes.

## Goals / Non-Goals

**Goals:**
- Introduce an explicit batch tag update mode that supports both replace and append semantics.
- Preserve current behavior for callers that do not send the new mode field.
- Keep the node batch tag modal aligned with backend behavior through clear mode selection and explanatory copy.
- Ensure nodes are marked as undeployed only when their effective tag set actually changes.

**Non-Goals:**
- Add a third “remove selected tags” or “clear only” mode.
- Redesign single-node tag editing behavior.
- Change tag data modeling, tag authorization semantics, or deployment history structure.
- Expand batch actions beyond node tag adjustment.

## Decisions

### Decision: Model the new behavior as an explicit request mode with replace as the default

`NodeBatchTagUpdateRequest` will gain a mode field, represented by a small enum or constrained string value such as `REPLACE` and `APPEND`. When the mode is absent or blank, the backend will treat it as `REPLACE` so existing clients preserve current behavior.

Alternatives considered:
- Infer mode from whether the request includes an extra boolean. Rejected because booleans are less self-documenting and harder to extend later.
- Change the endpoint path for append mode. Rejected because the behavior belongs to the same batch tag action and should share validation and response structure.

### Decision: Compute the target tag set in the service layer and compare against the current effective set

`NodeService.batchUpdateNodeTags` should resolve the selected tags once, then compute each node's target tag IDs based on the chosen mode:
- `REPLACE`: target set is exactly the selected tags.
- `APPEND`: target set is the union of current tags and selected tags.

Only nodes whose effective tag set changes should be marked `deployed = 0` and counted as updated.

Alternatives considered:
- Reuse `TagService.updateNodeTags` for every node. Rejected because batch mode already updates managed entities directly, and append mode needs per-node target-set computation before persistence.
- Always mark all selected nodes as changed. Rejected because it produces misleading counts and unnecessary undeploy state changes.

### Decision: Keep the modal selection simple with a required mode choice and mode-specific helper text

The batch tag modal will include a visible mode selector, such as radio buttons or a segmented choice, defaulted to replace. The warning/help text should change with the selected mode so operators understand whether tags will be overwritten or only appended.

Alternatives considered:
- Hide append behind a secondary button. Rejected because it makes the behavior easier to miss and harder to review before submit.
- Default to append. Rejected because it would silently change established behavior for current users.

## Risks / Trade-offs

- [Operators misread the selected mode] -> Keep replace as the default, show mode-specific descriptive text, and place the selector close to the tag checklist.
- [Append mode reports false positives] -> Compare the final target tag set against the current tag set before incrementing updated counts or marking undeployed.
- [Invalid mode values reach the backend] -> Normalize blanks to replace and reject unsupported values with a clear 400-level error.
- [Future capability overlap once the baseline specs are applied] -> Scope this spec narrowly to the batch tag action so it can later merge into a broader node-management capability if desired.

## Migration Plan

No data migration is required. Deploy the backend and frontend changes together so the new UI can send the mode field, while older callers continue to work because missing mode defaults to replace. Rollback is straightforward because the previous behavior is the replace path.

## Open Questions

- Whether the mode selector labels should use “覆盖” / “新增” only, or include brief English/internal enum mapping in the UI helper text.
- Whether the success toast should include the chosen mode in the returned message, or only rely on counts plus existing UI wording.
