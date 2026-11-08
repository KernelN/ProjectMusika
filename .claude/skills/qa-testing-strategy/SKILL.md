---
name: qa-testing-strategy
description: Designs risk-based test strategy for software delivery. Use when defining coverage, setting CI gates, managing flaky tests, choosing test layers, or establishing release criteria.
compatibility: Universal agent skill (Antigravity, Claude Code, OpenCode)
metadata:
  category: quality_assurance
  version: "1.5.0"
---

# QA Testing Strategy

Risk-based quality engineering guidance for modern software delivery.

## Core Principles

- **Prefer the smallest layer that can prove the behavior**: Unit > Integration > End-to-End.
- **Keep pre-merge gates fast**: Static analysis, linting, unit tests, and targeted component contracts.
- **Targeted executions**: Prefer targeted batch reruns locally; reserve full E2E suites for deploy gates or scheduled regression runs.
- **Layer constraint**: Use full E2E tests only for critical user journeys or risk scenarios that cannot be safely proven lower down in the stack.

## Testing Pyramid for Android & Modern Software

1. **Unit Tests (70%)**:
   - Pure business logic, ViewModels, repository unit tests, StateFlow reducers.
   - Fast, deterministic JVM tests without emulator requirement.
   - AAA pattern: Arrange, Act, Assert.

2. **Integration / Component Tests (20%)**:
   - Room database queries, SQLite migrations, repository integration with mocks.
   - Robolectric tests for Compose layouts and lifecycle flows.
   - Validate contracts between data source and domain layer.

3. **End-to-End & UI Tests (10%)**:
   - Espresso and Compose Test Rule for critical user journeys (e.g., Audio Playback engine, Media Session synchronization).
   - Mock network and hardware boundaries where appropriate to eliminate flake.

## Flake Control & Determinism

- Seed pseudo-random generators with a fixed seed per test run and print it on failure.
- Avoid custom sleeps; use Compose test clocks (`waitUntil`, `advanceTimeBy`) and idling resources.
- Guard against AI-generated test debt: ensure assertions are strict, meaningful, and never weakened to bypass failing tests.
