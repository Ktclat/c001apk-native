"""Additional whole-runner qualification, separate from the byte-restored status parser."""
import re

def qualify(raw):
    lines=raw.decode('utf-8',errors='replace').splitlines()
    terminals=[(index,int(match[1])) for index,line in enumerate(lines)
               if (match:=re.fullmatch(r'INSTRUMENTATION_CODE: (-?\d+)',line))]
    forbidden=[]
    for line in lines:
        if re.match(r'INSTRUMENTATION_(FAILED|ABORTED|ERROR)\b',line): forbidden.append('runner_failure_marker')
        if re.match(r'INSTRUMENTATION_(RESULT|STATUS):\s*(shortMsg|longMsg)\s*=',line): forbidden.append('runner_exception_message')
        if re.search(r'Process crashed\.|INSTRUMENTATION_RUN_FAILED',line): forbidden.append('process_crash_or_run_failed')
    single_normal=len(terminals)==1 and terminals[0][1]==-1
    # Android am -w emits the final code last; never accept trailing new test/status evidence.
    no_post_status=bool(single_normal and not any(line.startswith(('INSTRUMENTATION_STATUS:','INSTRUMENTATION_STATUS_CODE:',
        'INSTRUMENTATION_RESULT:','INSTRUMENTATION_CODE:')) for line in lines[terminals[0][0]+1:]))
    return {'qualified':single_normal and no_post_status and not forbidden,
            'terminalCount':len(terminals),'terminalCodes':[code for _,code in terminals],
            'noPostTerminalStatus':no_post_status,'failureCategories':sorted(set(forbidden)),
            'scope':'Whole-runner gate only; matching per-test starts/identities/status PASS are additionally required'}
