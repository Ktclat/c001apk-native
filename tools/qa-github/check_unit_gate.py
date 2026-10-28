#!/usr/bin/env python3
"""Require a successful ordinary source gate on this exact manual-dispatch commit."""
import json
import os
from pathlib import Path
import re
import urllib.request

sha = os.environ["GITHUB_SHA"]
assert re.fullmatch(r"[0-9a-f]{40}", sha)
assert os.environ.get("QA_APPROVED_SHA") == sha, "Approved SHA must equal the checked-out workflow commit"
assert os.environ.get("QA_NETWORK_APPROVED") == "true", "Network boundary not explicitly approved"
assert os.environ.get("GITHUB_REF") == "refs/heads/test/native-media-recovery-20261028", "No main dispatch"
repository = os.environ["GITHUB_REPOSITORY"]
assert re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
url = f"https://api.github.com/repos/{repository}/actions/workflows/android.yml/runs?head_sha={sha}&status=completed&per_page=100"
request = urllib.request.Request(url, headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
    "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
with urllib.request.urlopen(request, timeout=20) as response:
    runs = json.load(response)["workflow_runs"]
accepted = [r for r in runs if r["head_sha"] == sha and r["conclusion"] == "success" and r["event"] == "push"
            and r["head_branch"] == "test/native-media-recovery-20261028"]
assert accepted, "No ordinary push source gate success for exact SHA; do not build or boot"
run = max(accepted, key=lambda r: r["id"])
output = Path(os.environ["QA_PUBLIC"]) / "source-gate.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({"headSha": sha, "runId": run["id"], "url": run["html_url"],
    "event": run["event"], "conclusion": run["conclusion"], "runtimeUiApproval": "PENDING"}, indent=2) + "\n")
