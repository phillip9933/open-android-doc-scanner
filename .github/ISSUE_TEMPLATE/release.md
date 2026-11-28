---
name: Release tracking
about: Track stable-version scope and evidence-based promotion gates
labels: "type:release"
---

## Goal and target

Target stable version:
Owner (one person):
Supported platforms:
Milestone:

## Scope and included work

Link issues; state exclusions. Do not promise unestimated dates.

## Done when / promotion gates

### Planned → Alpha
- [ ] Scope, acceptance criteria and supported platforms recorded.
- [ ] Installable build exists (link below).

### Alpha → Beta
- [ ] Intended release features implemented.
- [ ] Core workflows pass.
- [ ] Remaining limitations documented.

### Beta → RC
- [ ] Release-blocking bugs resolved.
- [ ] Required regression and upgrade/migration tests pass.
- [ ] Relevant data-safety tests pass.
- [ ] Scope frozen.

### RC → Stable
- [ ] Final candidate validated. Record its commit and the result of the [Daybreak release check](https://github.com/phillip9933/open-android-doc-scanner/blob/main/docs/RELEASE.md#daybreak-release-check) before tagging.
- [ ] Release notes and known limitations ready.
- [ ] Signed/checksummed artifacts verified where applicable.
- [ ] Release published. Confirm the tag resolves to the evaluated candidate commit.

## Dependencies and remaining blockers

Record blockers and what unblocks them. Link other repositories' milestones if relevant.

## Verification and build/release links

Record evidence for each gate. Move back to Beta if a feature enters after RC. Done means complete work; Stable means published.
