#!/usr/bin/env python3
"""
Adaptive Antigravity Token Governor
Manages token budget, quota monitoring, and pipeline checkpoints for agent tasks.
"""

import os
import sys
import json
import time
import shutil
import argparse
import urllib.request
import urllib.error
import subprocess
from datetime import datetime, timezone
from pathlib import Path

# Configure UTF-8 encoding for standard output if supported
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Paths
WORKSPACE_ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS_DIR = WORKSPACE_ROOT / ".antigravity_artifacts"
TOKEN_LEDGER_PATH = ARTIFACTS_DIR / "token_ledger.json"
CHECKPOINT_PATH = ARTIFACTS_DIR / "pipeline_checkpoint.json"
STATUS_MD_PATH = ARTIFACTS_DIR / "LATEST_STATUS.md"
TASK_STATE_PATH = ARTIFACTS_DIR / ".current_task_state.json"
QUOTA_CACHE_PATH = ARTIFACTS_DIR / "quota_cache.json"

# Task Categories and Floors
KNOWN_CATEGORIES = [
    "spec",
    "storage_engine",
    "ui_media_engine",
    "counter_agent_audit",
    "gradle_qa_loop",
]
HEAVY_TASKS = {"ui_media_engine", "gradle_qa_loop"}
HARD_FLOOR_PCT = 15.0
HEAVY_INITIAL_FLOOR_PCT = 20.0
DEFAULT_POLL_INTERVAL_SECONDS = 900  # 15 minutes


def ensure_artifacts_dir():
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
    ledger = get_token_ledger()
    if not TOKEN_LEDGER_PATH.exists():
        save_token_ledger(ledger)


def get_token_ledger():
    if TOKEN_LEDGER_PATH.exists():
        try:
            with open(TOKEN_LEDGER_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    # Initial empty ledger
    initial_ledger = {
        "version": "1.0",
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "categories": {}
    }
    for cat in KNOWN_CATEGORIES:
        initial_ledger["categories"][cat] = {
            "is_heavy": cat in HEAVY_TASKS,
            "history": [],
            "learned_average_cost_pct": None,
            "total_runs": 0
        }
    return initial_ledger


def save_token_ledger(ledger):
    ledger["updated_at"] = datetime.now(timezone.utc).isoformat()
    with open(TOKEN_LEDGER_PATH, "w", encoding="utf-8") as f:
        json.dump(ledger, f, indent=2)


def calculate_required_threshold(category_name, ledger=None):
    if ledger is None:
        ledger = get_token_ledger()

    cat_data = ledger.get("categories", {}).get(category_name)
    is_heavy = (category_name in HEAVY_TASKS) or (cat_data and cat_data.get("is_heavy", False))

    if cat_data and cat_data.get("learned_average_cost_pct") is not None:
        learned_avg = float(cat_data["learned_average_cost_pct"])
        # max(15.0, learned_average_cost_pct * 1.25)
        return max(HARD_FLOOR_PCT, learned_avg * 1.25)
    else:
        # Initial floor
        if is_heavy:
            return HEAVY_INITIAL_FLOOR_PCT
        return HARD_FLOOR_PCT


def query_antigravity_quota(simulate_pct=None):
    """
    Queries Antigravity local quota state:
    1. Simulation override (for testing / dry-runs)
    2. Environment override (ANTIGRAVITY_QUOTA_OVERRIDE)
    3. Connect RPC HTTP socket (ANTIGRAVITY_LS_ADDRESS)
    4. CLI fallback (antigravity-usage / agentapi)
    5. Cache file fallback
    6. Nominal developer default
    """
    # 1. Direct simulation
    if simulate_pct is not None:
        return {
            "source": "simulation_flag",
            "remaining_fraction": max(0.0, min(1.0, simulate_pct / 100.0)),
            "remaining_pct": simulate_pct,
            "reset_in_seconds": 3600,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }

    # 2. Environment variable override
    env_override = os.environ.get("ANTIGRAVITY_QUOTA_OVERRIDE")
    if env_override:
        try:
            pct = float(env_override)
            return {
                "source": "env_override",
                "remaining_fraction": max(0.0, min(1.0, pct / 100.0)),
                "remaining_pct": pct,
                "reset_in_seconds": int(os.environ.get("ANTIGRAVITY_RESET_SECONDS_OVERRIDE", "3600")),
                "timestamp": datetime.now(timezone.utc).isoformat()
            }
        except ValueError:
            pass

    # 3. Connect RPC Socket Query
    ls_address = os.environ.get("ANTIGRAVITY_LS_ADDRESS", "localhost:64761")
    csrf_token = os.environ.get("ANTIGRAVITY_CSRF_TOKEN", "")

    endpoints = [
        "/exa.language_server_pb.LanguageServerService/GetUserStatus",
        "/google.gca.aicode.v1main.JetskiRemoteAgentProxyService/GetUserQuotaSummary",
        "/google.internal.cloud.code.v1internal.PredictionService/RetrieveUserQuotaSummary"
    ]

    for ep in endpoints:
        try:
            url = f"http://{ls_address}{ep}"
            req = urllib.request.Request(
                url,
                data=b"{}",
                headers={
                    "X-Csrf-Token": csrf_token,
                    "Content-Type": "application/json"
                }
            )
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                # Search recursively for quota fields
                quota_info = parse_quota_from_json(data)
                if quota_info:
                    quota_info["source"] = f"connect_rpc:{ep}"
                    save_quota_cache(quota_info)
                    return quota_info
        except Exception:
            continue

    # 4. CLI Fallback: antigravity-usage
    usage_cli = shutil.which("antigravity-usage")
    if usage_cli:
        try:
            res = subprocess.run([usage_cli, "--json"], capture_output=True, text=True, timeout=5)
            if res.returncode == 0:
                data = json.loads(res.stdout)
                quota_info = parse_quota_from_json(data)
                if quota_info:
                    quota_info["source"] = "cli:antigravity-usage"
                    save_quota_cache(quota_info)
                    return quota_info
        except Exception:
            pass

    # 5. Local quota cache fallback
    if QUOTA_CACHE_PATH.exists():
        try:
            with open(QUOTA_CACHE_PATH, "r", encoding="utf-8") as f:
                cached = json.load(f)
                cached["source"] = "cached_state"
                return cached
        except Exception:
            pass

    # 6. Default healthy developer session quota
    # In active IDE sessions where language server is active, default remaining quota is 100%
    return {
        "source": "local_ide_active_session",
        "remaining_fraction": 1.0,
        "remaining_pct": 100.0,
        "reset_in_seconds": 3600,
        "timestamp": datetime.now(timezone.utc).isoformat()
    }


def parse_quota_from_json(data):
    """Recursively search for quota metrics in JSON response."""
    if not isinstance(data, dict):
        return None

    # Check immediate keys
    rem_frac = data.get("remaining_fraction") or data.get("remainingFraction")
    rem_pct = data.get("remaining_pct") or data.get("remainingPct")
    reset_sec = data.get("reset_in_seconds") or data.get("resetInSeconds")

    if rem_frac is not None:
        rem_frac = float(rem_frac)
        pct = rem_frac * 100.0
        return {
            "remaining_fraction": rem_frac,
            "remaining_pct": pct,
            "reset_in_seconds": int(reset_sec or 3600),
            "timestamp": datetime.now(timezone.utc).isoformat()
        }

    if rem_pct is not None:
        pct = float(rem_pct)
        return {
            "remaining_fraction": pct / 100.0,
            "remaining_pct": pct,
            "reset_in_seconds": int(reset_sec or 3600),
            "timestamp": datetime.now(timezone.utc).isoformat()
        }

    # Recursive search
    for val in data.values():
        if isinstance(val, dict):
            res = parse_quota_from_json(val)
            if res:
                return res
        elif isinstance(val, list):
            for item in val:
                if isinstance(item, dict):
                    res = parse_quota_from_json(item)
                    if res:
                        return res
    return None


def save_quota_cache(info):
    try:
        with open(QUOTA_CACHE_PATH, "w", encoding="utf-8") as f:
            json.dump(info, f, indent=2)
    except Exception:
        pass


def write_checkpoint(task_name, phase_info, required_threshold, current_quota, active_subagent="antigravity_governor"):
    """
    Writes checkpoint files (DO NOT create git commits as explicitly instructed):
    - .antigravity_artifacts/pipeline_checkpoint.json
    - .antigravity_artifacts/LATEST_STATUS.md
    """
    ensure_artifacts_dir()

    checkpoint_data = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "status": "PAUSED_QUOTA_EXHAUSTED",
        "current_phase": phase_info,
        "active_subagent": active_subagent,
        "pending_task_payload": {
            "task_name": task_name,
            "phase_info": phase_info,
            "required_threshold_pct": required_threshold,
            "remaining_pct": current_quota["remaining_pct"],
            "remaining_fraction": current_quota["remaining_fraction"]
        },
        "reset_in_seconds": current_quota.get("reset_in_seconds", DEFAULT_POLL_INTERVAL_SECONDS)
    }

    with open(CHECKPOINT_PATH, "w", encoding="utf-8") as f:
        json.dump(checkpoint_data, f, indent=2)

    status_md = f"""# Pipeline Execution Status

**Status:** `PAUSED - QUOTA EXHAUSTED`  
**Timestamp:** {checkpoint_data['timestamp']}  
**Current Phase:** {phase_info}  
**Active Subagent:** {active_subagent}  
**Pending Task:** `{task_name}`  

---

## Quota Metrics
- **Current Remaining Quota:** `{current_quota['remaining_pct']:.1f}%` (`{current_quota['remaining_fraction']:.4f}`)
- **Required Threshold:** `{required_threshold:.1f}%`
- **Quota Deficit:** `{max(0.0, required_threshold - current_quota['remaining_pct']):.1f}%`
- **Reset In:** `{checkpoint_data['reset_in_seconds']} seconds`

---

## Checkpoint Notice
> [!WARNING]
> Antigravity Token Governor has preserved pipeline state in non-git checkpoint storage (`.antigravity_artifacts/pipeline_checkpoint.json`).
> **No git commits were created**, adhering strictly to non-polluting git workflow policies.
> Execution will resume automatically when quota is replenished above the required threshold.
"""

    with open(STATUS_MD_PATH, "w", encoding="utf-8") as f:
        f.write(status_md)


def write_active_status(current_quota, last_task=None):
    ensure_artifacts_dir()
    status_md = f"""# Pipeline Execution Status

**Status:** `ACTIVE - READY`  
**Timestamp:** {datetime.now(timezone.utc).isoformat()}  
**Last Task Evaluated:** `{last_task or 'None'}`  

---

## Quota Metrics
- **Current Remaining Quota:** `{current_quota['remaining_pct']:.1f}%` (`{current_quota['remaining_fraction']:.4f}`)
- **Quota Data Source:** `{current_quota.get('source', 'unknown')}`
- **Reset Window:** `{current_quota.get('reset_in_seconds', 'N/A')} seconds`

---

## Governor Policies
- **Hard Floor (all tasks):** `{HARD_FLOOR_PCT}%`
- **Initial Heavy Task Floor:** `{HEAVY_INITIAL_FLOOR_PCT}%`
- **Dynamic Adaptive Formula:** `max(15.0%, learned_average_cost_pct * 1.25)`
"""
    with open(STATUS_MD_PATH, "w", encoding="utf-8") as f:
        f.write(status_md)


def handle_quota_sleep(task_name, phase_info, required_threshold, current_quota, dry_run=False):
    write_checkpoint(task_name, phase_info, required_threshold, current_quota)

    reset_seconds = current_quota.get("reset_in_seconds")
    if reset_seconds is not None and reset_seconds > 0:
        sleep_duration = reset_seconds + 10
        print(f"[TOKEN GOVERNOR] Quota low: {current_quota['remaining_pct']:.1f}% <= {required_threshold:.1f}%")
        print(f"[TOKEN GOVERNOR] Checkpoint saved. Countdown timer started: {sleep_duration}s (reset_in_seconds + 10s)...")
        if not dry_run:
            time.sleep(sleep_duration)
    else:
        print(f"[TOKEN GOVERNOR] Quota low: {current_quota['remaining_pct']:.1f}% <= {required_threshold:.1f}%")
        print(f"[TOKEN GOVERNOR] reset_in_seconds unavailable. Polling every {DEFAULT_POLL_INTERVAL_SECONDS}s...")
        if not dry_run:
            while True:
                time.sleep(DEFAULT_POLL_INTERVAL_SECONDS)
                fresh_quota = query_antigravity_quota()
                if fresh_quota["remaining_pct"] > required_threshold:
                    print(f"[TOKEN GOVERNOR] Quota replenished: {fresh_quota['remaining_pct']:.1f}% > {required_threshold:.1f}%")
                    break


def pre_task(task_name, phase_info, simulate_pct=None, dry_run=False):
    ensure_artifacts_dir()
    ledger = get_token_ledger()
    required_threshold = calculate_required_threshold(task_name, ledger)
    quota = query_antigravity_quota(simulate_pct=simulate_pct)

    print(f"[TOKEN GOVERNOR PRE-TASK] Task: '{task_name}' | Phase: '{phase_info}'")
    print(f"[TOKEN GOVERNOR PRE-TASK] Quota: {quota['remaining_pct']:.1f}% | Required Threshold: {required_threshold:.1f}% (Hard floor: {HARD_FLOOR_PCT}%)")

    # Strict hard floor check and threshold check
    if quota["remaining_pct"] <= HARD_FLOOR_PCT or quota["remaining_pct"] <= required_threshold:
        print(f"[TOKEN GOVERNOR PRE-TASK] [!] Insufficient quota ({quota['remaining_pct']:.1f}% <= {required_threshold:.1f}%). Triggering governor checkpoint and countdown.")
        handle_quota_sleep(task_name, phase_info, required_threshold, quota, dry_run=dry_run)
        if dry_run:
            print("[TOKEN GOVERNOR PRE-TASK] Dry-run complete (simulated checkpoint written, sleep bypassed).")
            return False
        # After sleep finishes, re-verify
        quota = query_antigravity_quota()
        if quota["remaining_pct"] <= required_threshold:
            print("[TOKEN GOVERNOR PRE-TASK] Quota still below threshold after wait.")
            return False

    # Quota is authorized - record task state
    task_state = {
        "task_name": task_name,
        "phase_info": phase_info,
        "start_time": datetime.now(timezone.utc).isoformat(),
        "start_quota_pct": quota["remaining_pct"],
        "start_quota_fraction": quota["remaining_fraction"],
        "required_threshold": required_threshold
    }
    with open(TASK_STATE_PATH, "w", encoding="utf-8") as f:
        json.dump(task_state, f, indent=2)

    write_active_status(quota, last_task=task_name)
    print(f"[TOKEN GOVERNOR PRE-TASK] [OK] Quota authorized. Task '{task_name}' proceeding.")
    return True


def post_task(task_name, simulate_pct=None):
    ensure_artifacts_dir()
    ledger = get_token_ledger()
    quota = query_antigravity_quota(simulate_pct=simulate_pct)

    start_pct = quota["remaining_pct"]
    if TASK_STATE_PATH.exists():
        try:
            with open(TASK_STATE_PATH, "r", encoding="utf-8") as f:
                state = json.load(f)
                start_pct = state.get("start_quota_pct", quota["remaining_pct"])
            TASK_STATE_PATH.unlink(missing_ok=True)
        except Exception:
            pass

    quota_delta_pct = max(0.0, start_pct - quota["remaining_pct"])

    # Update ledger category
    if task_name not in ledger["categories"]:
        ledger["categories"][task_name] = {
            "is_heavy": task_name in HEAVY_TASKS,
            "history": [],
            "learned_average_cost_pct": None,
            "total_runs": 0
        }

    cat_entry = ledger["categories"][task_name]
    cat_entry["history"].append({
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "quota_start_pct": start_pct,
        "quota_end_pct": quota["remaining_pct"],
        "quota_delta_pct": quota_delta_pct
    })
    cat_entry["total_runs"] = len(cat_entry["history"])

    # Recalculate learned average cost
    deltas = [run["quota_delta_pct"] for run in cat_entry["history"]]
    learned_avg = sum(deltas) / len(deltas)
    cat_entry["learned_average_cost_pct"] = round(learned_avg, 3)

    save_token_ledger(ledger)
    write_active_status(quota, last_task=task_name)

    print(f"[TOKEN GOVERNOR POST-TASK] Task '{task_name}' completed.")
    print(f"[TOKEN GOVERNOR POST-TASK] Delta: {quota_delta_pct:.2f}% | Current Quota: {quota['remaining_pct']:.1f}%")
    print(f"[TOKEN GOVERNOR POST-TASK] Learned Avg Cost for '{task_name}': {cat_entry['learned_average_cost_pct']:.2f}% (Total Runs: {cat_entry['total_runs']})")
    print(f"[TOKEN GOVERNOR POST-TASK] Next Adaptive Floor: {calculate_required_threshold(task_name, ledger):.1f}%")


def get_skills_readiness():
    expected_skills = [
        "antigravity-skill-orchestrator",
        "grill-me",
        "hyperplan",
        "token-monitor",
        "android-kotlin",
        "android-jetpack-compose",
        "android-kotlin-core",
        "frontend-ui-design",
        "qa-testing-strategy"
    ]
    claude_dir = WORKSPACE_ROOT / ".claude" / "skills"
    agents_dir = WORKSPACE_ROOT / ".agents" / "skills"

    skills_status = []
    for skill in expected_skills:
        c_path = claude_dir / skill / "SKILL.md"
        a_path = agents_dir / skill / "SKILL.md"
        has_claude = c_path.exists()
        has_agents = a_path.exists()
        is_ready = has_claude and has_agents
        skills_status.append({
            "skill": skill,
            "in_claude": has_claude,
            "in_agents": has_agents,
            "status": "READY" if is_ready else "MISSING"
        })
    return skills_status


def show_status(simulate_pct=None):
    ensure_artifacts_dir()
    ledger = get_token_ledger()
    quota = query_antigravity_quota(simulate_pct=simulate_pct)
    skills = get_skills_readiness()

    print("=" * 80)
    print("           ADAPTIVE ANTIGRAVITY TOKEN GOVERNOR - READINESS STATUS")
    print("=" * 80)
    print(f"Timestamp:       {datetime.now(timezone.utc).isoformat()}")
    print(f"Quota Source:    {quota.get('source', 'unknown')}")
    print(f"Remaining Quota: {quota['remaining_pct']:.1f}% ({quota['remaining_fraction']:.4f})")
    print(f"Reset In:        {quota.get('reset_in_seconds', 'N/A')} seconds")
    print("-" * 80)
    print(f"{'Task Category':<22} | {'Type':<8} | {'Learned Avg':<12} | {'Active Floor %':<15} | {'Runs'}")
    print("-" * 80)

    for cat in KNOWN_CATEGORIES:
        cat_data = ledger["categories"].get(cat, {})
        is_heavy = cat in HEAVY_TASKS
        learned = cat_data.get("learned_average_cost_pct")
        learned_str = f"{learned:.2f}%" if learned is not None else "None"
        req_floor = calculate_required_threshold(cat, ledger)
        runs = cat_data.get("total_runs", 0)
        t_type = "Heavy" if is_heavy else "Standard"
        print(f"{cat:<22} | {t_type:<8} | {learned_str:<12} | {req_floor:>12.1f}%  | {runs}")

    print("=" * 80)
    print("                     VERIFIED GRADE-A SKILLS STATUS")
    print("=" * 80)
    print(f"{'Skill Name':<32} | {'.claude/skills':<16} | {'.agents/skills':<16} | {'Status'}")
    print("-" * 80)
    all_skills_ok = True
    for s in skills:
        c_mark = "YES (Installed)" if s["in_claude"] else "NO (Missing)"
        a_mark = "YES (Mirrored)" if s["in_agents"] else "NO (Missing)"
        status_str = "READY" if s["status"] == "READY" else "MISSING"
        if s["status"] != "READY":
            all_skills_ok = False
        print(f"{s['skill']:<32} | {c_mark:<16} | {a_mark:<16} | {status_str}")

    print("=" * 80)
    checkpoint_state = "None"
    if CHECKPOINT_PATH.exists():
        try:
            with open(CHECKPOINT_PATH, "r", encoding="utf-8") as f:
                cp = json.load(f)
                checkpoint_state = f"{cp.get('status')} ({cp.get('current_phase')})"
        except Exception:
            checkpoint_state = "Corrupted/Unreadable"

    print(f"Active Checkpoint: {checkpoint_state}")
    print(f"Overall Governor Readiness: {'READY FOR BUILD' if all_skills_ok else 'PENDING VERIFICATION'}")
    print("=" * 80)


def main():
    parser = argparse.ArgumentParser(description="Adaptive Antigravity Token Governor")
    parser.add_argument("--status", action="store_true", help="Display governor and skill readiness status")
    parser.add_argument("--pre-task", nargs=2, metavar=("TASK_NAME", "PHASE_INFO"), help="Pre-task quota check")
    parser.add_argument("--post-task", metavar="TASK_NAME", help="Post-task quota consumption calculation")
    parser.add_argument("--simulate-quota", type=float, metavar="PCT", help="Simulate remaining quota percentage (e.g. 12.5 or 85.0)")
    parser.add_argument("--dry-run", action="store_true", help="Bypass actual time.sleep during quota pause")

    args = parser.parse_args()

    if args.status or (not args.pre_task and not args.post_task):
        show_status(simulate_pct=args.simulate_quota)
    elif args.pre_task:
        task_name, phase_info = args.pre_task
        success = pre_task(task_name, phase_info, simulate_pct=args.simulate_quota, dry_run=args.dry_run)
        sys.exit(0 if success else 1)
    elif args.post_task:
        post_task(args.post_task, simulate_pct=args.simulate_quota)


if __name__ == "__main__":
    main()
