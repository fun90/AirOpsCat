## 1. Backend Contract

- [x] 1.1 Extend `NodeBatchTagUpdateRequest` with a batch tag update mode and keep missing mode compatible with the current replace behavior.
- [x] 1.2 Update `NodeController.batchUpdateNodeTags` to pass the selected mode into the service and keep validation errors user-friendly.

## 2. Batch Tag Logic

- [x] 2.1 Refactor `NodeService.batchUpdateNodeTags` to support both replace and append target-set computation.
- [x] 2.2 Ensure updated and unchanged counts, missing-tag validation, and undeployed marking remain correct for both modes.

## 3. Admin Console

- [x] 3.1 Update the node batch tag modal to let administrators choose replace or append mode before saving.
- [x] 3.2 Refresh the helper/warning copy and submission payload so the UI clearly reflects the selected mode.

## 4. Verification

- [x] 4.1 Run the narrowest reasonable build or test command covering the modified DTO, controller, service, and template changes.
- [x] 4.2 Manually verify in the node console that replace mode overwrites tags and append mode preserves existing tags while adding new ones.
