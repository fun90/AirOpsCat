# OpenSpec Usage Rules

## Spec Location
- The ONLY valid OpenSpec directory is the project root: `./openspec`
- NEVER create or use OpenSpec inside `.codex/worktrees`

## Priority Rules
- If multiple openspec directories exist, ALWAYS use `./openspec`
- Ignore any openspec found in temporary worktree directories

## Behavior Rules
- All `/openspec:*` commands must operate on `./openspec`
- Do not generate duplicate openspec folders
- Do not copy openspec into worktrees

## Safety Constraints
- Treat `./openspec` as the single source of truth
- Any modification must be applied to project-level openspec

## If Conflict Happens
- Explicitly ask user before creating a new openspec
