---
name: token-monitor
description: Monitor Antigravity and AI token/quota usage and alert when any quota drops below a threshold (default 20%).
compatibility: Universal agent skill (Antigravity, Claude Code, OpenCode)
metadata:
  category: token_governance
  version: "1.0.0"
---

# Token Monitor

Monitors model token usage, quota exhaustion, and rate limit resets across agent execution loops.

## Core Directives

1. **Pre-Task Check**:
   - Check local quota fraction before scheduling expensive subagent tasks.
   - If quota is below 20% (or learned threshold), trigger checkpointing and sleep until reset window.

2. **Threshold Governance**:
   - Hard floor: 15% minimum remaining quota across all operations.
   - Initial threshold for heavy tasks (`ui_media_engine`, `gradle_qa_loop`): 20%.
   - Dynamic adaptive threshold: `max(15.0, learned_average_cost_pct * 1.25)`.

3. **Status Reporting**:
   - Track per-task token consumption in `.antigravity_artifacts/token_ledger.json`.
   - Record active task phase, payload, and remaining quota fraction.
   - Keep status synchronized in `.antigravity_artifacts/LATEST_STATUS.md`.
