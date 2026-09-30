---
name: create-pr
description: Create and push a new feature branch with a pull request following project conventions
allowed-tools: Bash(git:*), Bash(gh:*), Bash(./gradlew:*), Bash(python3 scripts/*), Read, Grep, Glob, Skill(technical-writer)
argument-hint: [branch-name] [pr-title]
---

# Create Pull Request

Automate the creation of a feature branch, commits, and pull request following all project conventions.

## Current State

- Current branch: !`git rev-parse --abbrev-ref HEAD`
- Git status: !`git status --short`
- Uncommitted changes: !`git diff --stat`

## Arguments

- `$ARGUMENTS[0]`: Branch name (e.g., `feat/my-feature` or just `my-feature`)
- `$ARGUMENTS[1]`: PR title (optional, will be generated from commits if not provided)

## Workflow Steps

### 1. Branch Creation

If not already on a feature branch:

- Create a new branch following naming conventions:
  - `feat/feature-name` for new features
  - `fix/bug-description` for bug fixes
  - `chore/maintenance-task` for maintenance
  - `hotfix/critical-fix` for production hotfixes
- Checkout the new branch

### 2. Staged Changes Review

- Review all staged and unstaged changes with `git diff`
- **Identify logical groups of changes for separate commits** (see step 3)
- Ensure no sensitive files are staged (.env, credentials, etc.)

### 3. Create Scoped Commits (MANDATORY: multiple commits)

**NEVER create a single monolithic commit for all changes.** Always split changes into multiple scoped commits, each representing one logical unit of work.

**How to split commits:**

1. Analyze all changed files and group them by concern:
   - Feature logic (screens, AppModel, services, backend calls)
   - UI/styling changes (composables, design tokens, core:ui components)
   - i18n (catalog sync, `i18n/android/` variants)
   - Refactoring (renaming, extracting, restructuring existing code)
   - Configuration (build, linting, CI)
   - Bug fixes
2. Each group becomes its own commit
3. Order commits logically: foundational changes first, dependent changes after

**Example split for a profile modal rework:**

```
refactor(ui): remove size parameter from DrafftButton
style(ui): add frosted header modifier and float animation
feat(profile): add account deletion flow
feat(profile): rework edit profile as a single scrollable page
chore(i18n): sync profile strings from the iphone catalog
```

**For each commit:**

1. Stage only the relevant files: `git add <specific-files>` (never `git add .` or `git add -A`)
2. Create commit following **MANDATORY** rules:
   - Format: `type(scope): description`
   - **ONE LINE ONLY**, multiline commits are forbidden
   - Types: feat, fix, docs, style, refactor, test, chore
   - Scope: required, the area touched (see `.agents/rules/commits.md`)
   - Description: lowercase, no period at end, start with a verb
   - Examples:
     - `feat(auth): add sms code verification`
     - `fix(chat): resolve composer alignment issue`
     - `refactor(model): extract profile completion logic`
     - `chore(i18n): sync session strings from the iphone catalog`

3. **NEVER use `--no-verify`**, all commits must pass pre-commit hooks
4. If hooks fail, fix the issues and retry

### 4. Run Verification

Before pushing, ensure quality:

```bash
./gradlew -p tools/jvmcheck compileKotlin test
python3 scripts/check-strings.py
python3 scripts/ci/design_lint.py && python3 scripts/ci/i18n_lint.py
./gradlew assembleLocalDebug
```

These must pass with zero errors. Fix any issues before proceeding. The last line needs the Android
SDK: without it, run the others and write in the PR that the Android build is left to CI.

### 5. Update Documentation (if needed)

Before pushing, check whether the branch's changes require README updates. Compare all commits on the branch against main and look for:

- New or changed **build settings** (keys in `config/<flavor>.properties`, `buildConfigField`s, flavors in `app/build.gradle.kts`)
- New or changed **scripts** in `scripts/` or checks in `tools/jvmcheck`
- New major **dependencies** added or removed (`gradle/libs.versions.toml`)
- New **modules** or packages
- Changes to **build/deployment** config or **quality gates** (`.github/workflows/`)

If any of these are detected, invoke `/technical-writer` to update `README.md` (and `AGENTS.md` when the conventions change) before continuing. The documentation commit(s) should use the `docs` type (e.g., `docs: update README for new env vars`).

If none of the above apply, skip this step.

### 5b. Check Legal Pages (if needed)

If the PR introduces changes that affect legal obligations, the legal pages (privacy, terms) may need
updating. They live in the `drafft-web` repository, not here. Look for:

- New **third-party services** or SDKs added (e.g., analytics, crash reporting, payment providers)
- Changes to **data collection** (new personal data fields, new tracking events, new Android permissions)
- Changes to **authentication** flow or required user information
- Changes to **data retention** or deletion behavior
- Addition of **paid features** or subscription model

If any of these are detected, say so in the PR's "Additional Changes" section ("Legal pages in
drafft-web need an update: ...") and tell the user. Don't edit another repository from this PR.

If none of the above apply, skip this step.

### 5c. Check Parity With the iPhone App (MANDATORY)

drafft-android ports the iPhone app (`../drafft`). When they disagree, the iPhone app is right.

- For a behaviour or UI change, name the Swift file(s) it ports or follows in the PR's "Motivation"
  section, and say whether the iPhone app already behaves this way.
- If the change only exists on Android (a platform need: system back, permissions, Google Play), say
  so and why.
- If `core/model/src/main/resources/i18n/*.json` or `keys.txt` changed through
  `scripts/sync-strings.py`, give the drafft commit the catalog was synced from. Strings are never
  added on Android only (see the `wording` skill).
- If `WORDING.md` or `DESIGN.md` changed, it must come from `../drafft/scripts/sync-wording.sh`
  (they're synced copies: never edited here).

### 6. Push Branch

Push the branch to remote with tracking:

```bash
git push -u origin <branch-name>
```

### 7. Create Pull Request

**CRITICAL: You MUST use the EXACT template structure below. No exceptions.**

Use this exact command structure:

```bash
gh pr create --title "<title>" --body "$(cat <<'EOF'
## Type of Change

- [ ] ✨ New feature
- [ ] 🐛 Bug fix
- [ ] 📝 Documentation
- [ ] 🔧 Configuration
- [ ] 🤖 CI/CD
- [ ] ♻️ Refactor
- [ ] 🎨 Style

## Summary

<Brief description of changes - 1-2 sentences>

## Motivation

<Why are these changes needed? What problem do they solve?>

## Changes

### Main Changes

- <Change 1>
- <Change 2>

### Additional Changes

- <Change 1 or "None">

## Testing

### Prerequisites

<List any prerequisites or "None">

### Test Steps

1. <Step 1>
2. <Step 2>
3. <Expected result>

## Documentation

- [x] No documentation changes needed

## Breaking Changes

- [x] No breaking changes

## Checklist

- [x] Compile check and unit tests pass (`./gradlew -p tools/jvmcheck compileKotlin test`)
- [x] String keys check passes (`python3 scripts/check-strings.py`)
- [x] Design and i18n lints pass (`scripts/ci/design_lint.py`, `scripts/ci/i18n_lint.py`)
- [x] Android build passes (`./gradlew assembleLocalDebug`), or left to CI (no Android SDK)
- [x] Same behaviour as the iPhone app, or the difference is explained
- [x] Code follows project conventions (AGENTS.md)
EOF
)"
```

**MANDATORY RULES:**

- The PR description MUST be written in **English**
- Check ONE or more types of change with `[x]`
- Fill ALL sections (use "None" or "N/A" if not applicable)
- Check all applicable items in Checklist
- Never skip or simplify this template

### 8. Update PR Details

After creation:

- Ensure title is concise and descriptive
- Fill all template sections appropriately
- Add relevant labels if applicable
- Request reviewers if needed

## Commit Message Examples

```
feat(auth): add biometric authentication support
fix(discover): resolve deck refresh issue
refactor(ui): extract DrafftButton from screens
chore(i18n): add android wording for play store sentences
docs(api): update authentication endpoints
style(components): apply consistent spacing
test(services): add user profile service tests
```

## PR Title Guidelines

- Keep under 70 characters
- Use imperative mood ("Add feature" not "Added feature")
- Be specific about what changes
- Include scope if helpful

Good: `feat(auth): add PIN code verification`
Bad: `Updated some auth stuff`

## Checklist Before PR

- [ ] Changes are split into multiple scoped commits (not one big commit)
- [ ] All commits follow conventional format
- [ ] No `--no-verify` was used
- [ ] Verify commands pass (AGENTS.md, "Verify")
- [ ] READMEs updated if env vars, scripts, deps, or structure changed (via `/technical-writer`)
- [ ] Legal pages flagged (drafft-web) if new third-party services, data collection, or permissions
- [ ] Parity with the iPhone app stated
- [ ] Branch name follows conventions
- [ ] PR description is complete
- [ ] No sensitive data in commits

## Returns

- **pr_url**: The URL of the created pull request
- **branch**: The name of the feature branch
- **commits**: List of commits included in the PR
