#!/usr/bin/env python3
"""Parse AndroidJUnitRunner status records without treating assumption skips as passes."""
import json,pathlib,sys
rows=[]
for arg in sys.argv[1:]:
 p=pathlib.Path(arg); fields={}
 for line in p.read_text().splitlines():
  if line.startswith('INSTRUMENTATION_STATUS: '):
   key,sep,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=')
   if sep: fields[key]=value
  elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
   code=int(line.split(': ',1)[1])
   if code!=1 and 'test' in fields:
    rows.append({'log':str(p),'class':fields.get('class'),'test':fields['test'],'status_code':code,'result':{0:'passed',-4:'assumption_skipped',-3:'ignored',-2:'failed',-1:'error'}.get(code,'unknown')})
   fields={}
passed=[r for r in rows if r['result']=='passed']; skips=[r for r in rows if r['result'] in ('assumption_skipped','ignored')]
print(json.dumps({'passed_invocations':len(passed),'unique_passed_scenarios':len({(r['class'],r['test']) for r in passed}),'skipped_invocations':len(skips),'failed_or_error_invocations':sum(r['result'] in ('failed','error','unknown') for r in rows),'records':rows},indent=2))
