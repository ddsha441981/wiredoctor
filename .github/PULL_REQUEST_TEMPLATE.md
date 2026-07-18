<!--
Thanks for contributing to WireDoctor! Please fill in the sections below.
See CONTRIBUTING.md for build/test conventions and the zero-intrusion posture.
-->

## What changed

<!-- A concise description of the change and the module(s) it touches. -->

## Why

<!-- The problem this solves. Link the issue it closes, e.g. "Closes #123". -->

## How it was tested

<!-- Unit tests added/updated, and anything you verified manually
     (e.g. against wiredoctor-test or a real application). -->

## Checklist

- [ ] `mvn verify` passes locally (tests green + JaCoCo 80% coverage gate holds)
- [ ] New behavior has tests; bug fixes include a test that fails without the fix
- [ ] Public API / behavior changes are documented (Javadoc, README/docs) and added to `CHANGELOG.md` under `[Unreleased]`
- [ ] Change respects the zero-intrusion posture (cannot crash the host app; no new core runtime dependencies without discussion)
- [ ] No publishing/version-bump/release config added (handled separately at v1.0.0)
