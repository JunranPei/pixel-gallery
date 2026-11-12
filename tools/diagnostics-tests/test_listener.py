import sys,unittest,json,tempfile,base64
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from viewer_listener import Listener

class Fake(Listener):
    def __init__(self):
        super().__init__('test-device');self.enabled=False;self.calls=[];self.wirePid=123
    def run(self,*args):
        self.calls.append(args)
        command=args[args.index('command')+1];nonce=args[args.index('request_id')+1]
        if command=='start':self.enabled=True
        if command=='stop':self.enabled=False
        response=dict(protocol=2,package=self.package,pid=self.wirePid,requestId=nonce,enabled=self.enabled)
        if command=='read':
            resource=args[args.index('resource')+1]
            if resource=='previous.log':return b'Broadcast completed: result=0'
            raw=b'{"viewerMemory":{"image:zoom:test":"unchanged"}}' if resource=='snapshot.json' else b'EVENT name=EXAMPLE key=test thread=main detail\n'
            response.update(data=base64.b64encode(raw).decode(),next=len(raw),eof=True)
        return b'Broadcast completed: result=-1, data="GALLERY_DIAG_V2:'+base64.b64encode(json.dumps(response).encode())+b'"'

class Tests(unittest.TestCase):
    def test_connect_does_not_start_or_refresh_and_targets_registered_receivers(self):
        f=Fake();self.assertFalse(f.status()['enabled']);self.assertEqual(len(f.calls),1)
        args=f.calls[0]
        self.assertEqual(args[:3],('shell','am','broadcast'))
        self.assertEqual(args[args.index('-f')+1],'0x40000000')
        self.assertNotIn('content',args);self.assertNotIn('start',args);self.assertNotIn('force-stop',args)
    def test_start_and_stop_are_verified(self):
        f=Fake();self.assertTrue(f.control('start')['enabled']);self.assertFalse(f.control('stop')['enabled'])
    def test_collection_preserves_memory_and_missing_log_is_reported(self):
        f=Fake()
        with tempfile.TemporaryDirectory() as folder:
            out=f.collect(folder);m=json.loads((out/'manifest.json').read_text(encoding='utf-8'))
            self.assertTrue((out/'current.log').exists());self.assertFalse((out/'previous.log').exists())
            self.assertIn('unavailable',m['logs']['previous']);self.assertFalse(f.enabled)
    def test_absent_receiver_does_not_fall_back_to_launching_app(self):
        f=Fake();calls=[]
        f.run=lambda *args:(calls.append(args) or b'Broadcast completed: result=0')
        with self.assertRaises(RuntimeError):f.status()
        self.assertEqual(len(calls),1)
    def test_process_change_is_rejected(self):
        f=Fake();f.status();f.wirePid=456
        with self.assertRaises(RuntimeError):f.control('capture')
    def test_wrong_package_response_is_rejected(self):
        f=Fake();original=f.run
        def changed(*args):
            raw=original(*args);start=raw.index(b'GALLERY_DIAG_V2:')+len(b'GALLERY_DIAG_V2:')
            body=json.loads(base64.b64decode(raw[start:-1]));body['package']='other'
            return raw[:start]+base64.b64encode(json.dumps(body).encode())+b'"'
        f.run=changed
        with self.assertRaises(RuntimeError):f.status()

if __name__=='__main__':unittest.main()
