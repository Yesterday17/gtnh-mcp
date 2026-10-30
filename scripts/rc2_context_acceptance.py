"""Opt-in acceptance against an isolated RC2 instance with the separate test fixture installed."""
import argparse, json, pathlib, sys, time, threading, urllib.request, queue
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--config',required=True,help='Isolated RC2 gtnhmcp.properties')
parser.add_argument('--control-dir',required=True,help='The separately enabled acceptance fixture directory')
parser.add_argument('--output-dir',required=True,help='Directory for JSON acceptance records')
options=parser.parse_args()
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
from mcp_probe import Client, read_config, PROTOCOL
config=pathlib.Path(options.config)
control=pathlib.Path(options.control_dir)
root=pathlib.Path(options.output_dir)
root.mkdir(parents=True,exist_ok=True)
status=json.loads((control/'status.json').read_text())
if not status.get('worldFolder','').startswith('gtnhmcp-acceptance-') or not status.get('ready'):
    raise SystemExit('This check requires a ready disposable acceptance world; it never operates a personal save.')
records=[]
def check(name, value, detail=None):
    records.append({'check':name,'passed':bool(value),'detail':detail})
    (root/'context-live-results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
    print(('PASS ' if value else 'FAIL ')+name, flush=True)
    if not value: raise AssertionError(name+': '+str(detail))
url,token = read_config(config)
c=Client(url,token); sub=Client(url,token)
ready=threading.Event(); notifications=queue.Queue(); live=[]
def read_stream():
    req=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,'Mcp-Session-Id':sub.session_id,'MCP-Protocol-Version':PROTOCOL,'Accept':'text/event-stream'})
    try:
        with sub.opener.open(req,timeout=40) as response:
            live.append(response);ready.set();data=[]
            for raw in response:
                line=raw.decode().rstrip('\r\n')
                if line.startswith('data:'):data.append(line[5:].lstrip())
                elif not line and data:
                    notifications.put(json.loads('\n'.join(data)));data=[]
    except Exception:
        ready.set()
def tool(name,args=None,error=False):
    out=c.request('tools/call',{'name':name,'arguments':args or {}})
    if out.get('isError') and not error:raise AssertionError(name+': '+str(out))
    return out if error else out['structuredContent']
def wait_status(predicate,timeout=30):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        s=json.loads((control/'status.json').read_text())
        if predicate(s):return s
        time.sleep(.2)
    raise AssertionError('Fixture status timeout')
def command(text,predicate=None):
    (control/'command.txt').write_text(text)
    if predicate: return wait_status(lambda s:s.get('lastCommand')==text and predicate(s))
def push(uri,timeout=8):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:event=notifications.get(timeout=max(.01,end-time.monotonic()))
        except queue.Empty:return False
        if event.get('method')=='notifications/resources/updated' and event.get('params',{}).get('uri')==uri:return True
    return False
try:
    init=c.initialize();sub.initialize()
    check('production MCP version and resources',init['protocolVersion']==PROTOCOL and init['serverInfo']['version']=='0.3.0' and init['capabilities']['resources']['subscribe'])
    definitions=c.request('tools/list')['tools'];check('all 15 tools',len(definitions)==15)
    byname={t['name']:t for t in definitions}
    check('local writes marked non-read-only',all(not byname[n]['annotations']['readOnlyHint'] for n in ['ack_message','update_project_memory','reply_in_game']))
    ctx=tool('get_context');world=ctx['worldId'];session=ctx['worldSessionId']
    check('real RC2 versions and active identity',ctx['targetPack']=='2.9.0-RC-2' and ctx['installedMods']['NotEnoughItems']=='2.8.155-GTNH' and world.startswith('w_'))
    rs=c.request('resources/list')['resources'];check('two context resources',len(rs)==2)
    for resource in rs:sub.request('resources/subscribe',{'uri':resource['uri']})
    thread=threading.Thread(target=read_stream,daemon=True);thread.start();check('GET SSE connected',ready.wait(5) and bool(live))
    overview=json.loads(c.request('resources/read',{'uri':'gtnh://context/messages'})['contents'][0]['text'])
    check('resource reports current world',overview['worldId']==world and overview['active'])
    start=overview['latestSeq']
    project=tool('get_project_memory',{'worldId':world});revision=project['revision']
    doc={'schemaVersion':1,'goals':['Acceptance: plan selected recipe'],'lines':[{'id':'fixture-furnace','status':'user_reported_built','dimension':0,'machines':[{'x':4,'y':65,'z':0}],'connections':[]}],'constraints':['Reuse available machines'],'evidence':[]}
    updated=tool('update_project_memory',{'worldId':world,'expectedRevision':revision,'project':doc})
    check('project persists revision and non-authoritative provenance',updated['revision']==revision+1 and updated['project']==doc and updated['verification']=='not_verified_by_game')
    check('real project resource push',push('gtnh://context/project'))
    conflict=tool('update_project_memory',{'worldId':world,'expectedRevision':revision,'project':{}},True)
    check('stale project write rejected',conflict['isError'] and 'revision_conflict' in json.dumps(conflict))
    for n,args in [('get_inventory',{}),('inspect_target',{}),('inspect_machine',{'x':4,'y':65,'z':0}),('find_machines',{'radius':16,'limit':20}),('search_items',{'query':'minecraft:iron_ingot','limit':5})]:
        check('existing '+n+' works with stateful transport',bool(tool(n,args)))
    for n,args in [('find_recipes',{'registryName':'minecraft:iron_ingot','limit':3}),('find_usages',{'registryName':'minecraft:soul_sand','limit':3})]:
        page=tool(n,args)
        for _ in range(80):
            if page['recipes'] or not page.get('cursor'):break
            page=tool(n,{'cursor':page['cursor'],'limit':3})
        check('existing '+n+' works',bool(page['recipes']));detail=tool('get_recipe',{'recipeId':page['recipes'][0]['recipeId']});check(n+' detailed recipe',bool(detail))
    output=control/'context-ui-result.json'
    if output.exists():output.unlink()
    command('test_context_ui')
    end=time.monotonic()+45
    while time.monotonic()<end:
        if output.exists():
            ui=json.loads(output.read_text())
            if ui.get('complete') or ui.get('status') in ['passed','failed'] or ui.get('passed') is not None:break
        time.sleep(.25)
    else:raise AssertionError('UI fixture timeout')
    print('UI fixture:',json.dumps(ui),flush=True)
    check('production GUI integration',ui.get('passed') is True or ui.get('status')=='passed',ui)
    check('real message resource push',push('gtnh://context/messages'))
    messages=tool('get_messages',{'worldId':world,'afterSequence':start,'limit':20})
    check('text and recipe messages; cancel did not enqueue',len(messages['messages'])==2,messages['retainedMessages'])
    text,recipe=messages['messages'];check('text-only attachment is null',text['attachment'] is None)
    check('exact recipe attachment',recipe['attachment']['type']=='recipe' and recipe['attachment']['data'].get('gregtech',{}).get('recipeMapId')==ui['messages'][1]['recipe']['gregtech']['recipeMapId'])
    check('captured session and timestamps',recipe['context']['worldSessionId']==session and bool(recipe['capturedAt']) and bool(recipe['publishedAt']))
    message=tool('get_message',{'worldId':world,'messageId':recipe['id']});check('full frozen attachment retrievable',message['attachment']==recipe['attachment'])
    ackargs={'worldId':world,'messageId':recipe['id'],'consumerId':'acceptance.receiver'}
    ack=tool('ack_message',ackargs);check('client receipt recorded', 'acceptance.receiver' in ack['acknowledgements']);check('receipt retry idempotent',tool('ack_message',ackargs)==ack)
    replyargs={'worldId':world,'messageId':recipe['id'],'replyId':'acceptance.reply.1','text':'Acceptance reply: inspect existing machines before connecting the new production line.'}
    reply=tool('reply_in_game',replyargs);check('reply displayed locally',reply['displayed'] and reply['scope']=='local_chat' and not reply['duplicate']);check('reply retry deduplicated',tool('reply_in_game',replyargs)['duplicate'])
    command('unload',lambda s:not s['worldLoaded'])
    absent=tool('reply_in_game',{**replyargs,'replyId':'acceptance.reply.absent'},True);check('reply rejected with no active world',absent['isError'])
    offline=tool('get_project_memory',{'worldId':world});check('known-world planning readable while unloaded',offline['project']==doc)
    command('rejoin',lambda s:s['worldLoaded'] and s['ready'])
    ctx2=tool('get_context');check('persistent world ID; new live session',ctx2['worldId']==world and ctx2['worldSessionId']!=session)
    check('project retained on rejoin',tool('get_project_memory',{'worldId':world})['project']==doc)
    check('frozen recipe retained on rejoin',tool('get_message',{'worldId':world,'messageId':recipe['id']})['attachment']==recipe['attachment'])
    (root/'context-live-message.json').write_text(json.dumps(recipe,ensure_ascii=False,indent=2))
    print('Live acceptance passed:',len(records),'checks',flush=True)
finally:
    sub.close();c.close()
