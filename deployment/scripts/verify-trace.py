#!/usr/bin/env python3
"""Requires running stack; verifies propagation, error/latency, and exported Java spans."""
import json, sys, time, urllib.request, urllib.error
base=sys.argv[1].rstrip('/') if len(sys.argv)>1 else 'http://localhost:8080'
jaeger=sys.argv[2].rstrip('/') if len(sys.argv)>2 else 'http://localhost:16686'
for scenario,expected in [('normal',200),('slow',200),('error',502)]:
 start=time.monotonic()
 request=urllib.request.Request(base+'/api/orders?scenario='+scenario,method='POST')
 try:response=urllib.request.urlopen(request,timeout=15)
 except urllib.error.HTTPError as ex:response=ex
 with response:
  assert response.status==expected,(scenario,response.status)
  body=json.load(response)
 elapsed=time.monotonic()-start
 trace_id=body['traceId']
 assert len(trace_id)==32 and int(trace_id,16)!=0,body
 if scenario!='error':assert body['inventory']['traceId']==trace_id,body
 if scenario=='slow':assert elapsed>=1.7,elapsed
 deadline=time.monotonic()+30
 while True:
  try:
   with urllib.request.urlopen(jaeger+'/api/traces/'+trace_id,timeout=5) as r: data=json.load(r)['data']
   if data:
    t=data[0];services={p['serviceName'] for p in t['processes'].values()}
    names={s['operationName'] for s in t['spans']}
    if {'order-service','inventory-service'}<=services and {'order.create','inventory.lookup'}<=names:break
  except (urllib.error.URLError,KeyError):pass
  if time.monotonic()>=deadline:raise AssertionError('Trace missing/incomplete: '+trace_id)
  time.sleep(1)
 if scenario=='error':
  for name in ['order.create','inventory.lookup']:
   assert any(s['operationName']==name and any(tag['key']=='error' and tag['value'] is True for tag in s.get('tags',[])) for s in t['spans']),name
 print(f'PASS {scenario}: HTTP {expected}, {elapsed:.2f}s, trace={trace_id}, services={sorted(services)}')
print('Browser span: click frontend buttons and check web-frontend in the same trace (this script does not run a browser).')
