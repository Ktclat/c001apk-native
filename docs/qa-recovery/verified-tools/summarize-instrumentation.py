#!/usr/bin/env python3
"""Read raw AndroidJUnitRunner statuses; never equate runner OK with a passed test.

Usage: summarize-instrumentation.py LOG [LOG ...]
A start without terminal status is INCOMPLETE, not implicitly fail/pass/skip.
Operator-abort attribution and scenario-vs-control grouping require the run receipt.
"""
import collections
import hashlib
import json
import pathlib
import re
import sys

labels = {0: 'PASS', -1: 'ERROR', -2: 'FAIL', -3: 'IGNORED', -4: 'ASSUMPTION'}
all_invocations = []
logs = []
for arg in sys.argv[1:]:
    path = pathlib.Path(arg)
    raw = path.read_bytes()
    current = {}
    pending = {}
    invocations = []
    for line in raw.decode('utf-8', errors='replace').splitlines():
        field = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test|current|numtests)=(.*)', line)
        if field:
            current[field[1]] = field[2]
        code = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if not code:
            continue
        value = int(code[1])
        if 'class' in current and 'test' in current:
            key = current['class'] + '#' + current['test']
            if value == 1:
                assert key not in pending, ('Duplicate unfinished start', str(path), key)
                pending[key] = dict(current)
            else:
                status = labels.get(value, 'UNKNOWN')
                if value == 0 and key not in pending:
                    status = 'ORPHAN_PASS_UNQUALIFIED'
                record = {'scenario': key, 'status_code': value, 'status': status,
                          'had_start': key in pending, 'raw_log': str(path)}
                pending.pop(key, None)
                invocations.append(record)
        current = {}
    for key in pending:
        invocations.append({'scenario': key, 'status': 'INCOMPLETE', 'raw_log': str(path)})
    all_invocations.extend(invocations)
    logs.append({'path': str(path), 'sha256': hashlib.sha256(raw).hexdigest(),
                 'counts': dict(collections.Counter(x['status'] for x in invocations)), 'invocations': invocations})
unique = collections.defaultdict(list)
for invocation in all_invocations:
    unique[invocation['scenario']].append(invocation['status'])
print(json.dumps({'counts_by_invocation': dict(collections.Counter(x['status'] for x in all_invocations)),
                  'unique_scenario_count': len(unique), 'unique_scenario_statuses': dict(unique),
                  'logs': logs, 'scope': 'Raw status parser only. Controls and operator-abort reasons are classified separately; no runner OK shortcut.'}, indent=2))
