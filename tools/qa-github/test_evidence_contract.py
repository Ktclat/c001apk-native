"""Pure fake-file contract checks, NOT compositor/decoder evidence; no guest/network/process."""
import json
from pathlib import Path
import tempfile
import unittest
import evidence_contract as contract

ROOT=Path(__file__).resolve().parents[2]
PREFIX='io.github.ktclat.c001apk.nativeapp.ui.'

class RequiredEvidence(unittest.TestCase):
    def populate(self,directory):
        # Deliberately not real PNGs: these unit bytes must NEVER be published as UI evidence.
        captures=[]
        for method,stages in contract.EMOTE.items(): captures += ['emotes-'+method+'-'+s for s in stages]
        for method,stages in contract.MEDIA.items(): captures += ['media-'+method+'-'+s for s in stages]
        for _method,(prefix,stages,extra) in contract.FIXED.items():
            captures += [prefix+'-'+s for s in stages]
            for name in extra: (directory/name).write_text('synthetic unit receipt')
            (directory/(prefix+'-requests.txt')).write_text('synthetic unit ledger')
        for prefix,table in [('emotes-',contract.EMOTE),('media-',contract.MEDIA)]:
            for method in table: (directory/(prefix+method+'-requests.txt')).write_text('synthetic unit ledger')
        captures += ['production-emotes-'+s for s in ('01-off','02-reentry-off','03-recreated-off','04-on')]
        for suffix in ('persistence','restored'): (directory/('production-emotes-'+suffix+'.txt')).write_text('synthetic unit receipt')
        for stage,oracle in [('formatted','copy_expected.txt'),('raw','copy_source.txt')]:
            captures += ['copy-'+stage+'-toolbar-original']
            for suffix in ('-sync.txt','-toolbar.xml','-toolbar-windowmanager.txt','-receipt.txt','-copy-action.txt','-select-all-action.txt'):
                (directory/('copy-'+stage+suffix)).write_text('synthetic unit receipt')
            (directory/('copy-'+stage+'-exact.txt')).write_bytes((ROOT/'app/src/androidTest/assets/oracles'/oracle).read_bytes())
        (directory/'emotes-fullReaderRawAndActualCopyPreserveOriginalTokensAndCode-requests.txt').write_text('synthetic unit ledger')
        for capture in captures:
            (directory/(capture+'.png')).write_bytes(b'\x89PNG\r\n\x1a\nFAKE_UNIT_ONLY')
            for suffix in ('.txt','-capture-trace.txt'): (directory/(capture+suffix)).write_text('synthetic unit receipt')
        for kind in ('default-https','production-media'): (directory/('boundary-'+kind+'.txt')).write_text('synthetic unit receipt')
        for name in ('gif-source-provenance.json','gif-before-temporal.txt'): (directory/name).write_text('{}')
        frames=[{'file':f'gif-original-{i:02}.png','accepted':i==2} for i in range(3)]
        for row in frames: (directory/row['file']).write_bytes(b'\x89PNG\r\n\x1a\nFAKE_UNIT_ONLY')
        (directory/'gif-temporal-trace.json').write_text(json.dumps({'settleMs':1500,'samplingBudgetMs':12000,'postSettleSamples':3,'imageHashes':['a','b'],'sourceFrameCount':2,'frames':frames}))
    def test_all_case_contracts_and_each_required_file_omission(self):
        plan=json.loads((ROOT/'docs/qa-recovery/new-plan/case-plan.json').read_text())
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);self.populate(directory)
            for case in plan['appCases']+plan['egressPreflightsSeparate']:
                with self.subTest(selector=case['selector']):
                    required=contract.validate(directory,case['selector'])
                    self.assertTrue(required)
                    for name in required:
                        path=directory/name;original=path.read_bytes();path.unlink()
                        try:
                            with self.assertRaises((AssertionError,FileNotFoundError)): contract.validate(directory,case['selector'])
                        finally: path.write_bytes(original)
    def test_copy_changed_content_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);self.populate(directory)
            with (directory/'copy-formatted-exact.txt').open('ab') as output: output.write('楼主层主置顶'.encode())
            with self.assertRaises(AssertionError): contract.validate(directory,PREFIX+'EmulatorInlineEmoticonsTest#fullReaderRawAndActualCopyPreserveOriginalTokensAndCode')
    def test_gif_static_partial_and_unsafe_frame_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);self.populate(directory);path=directory/'gif-temporal-trace.json';original=json.loads(path.read_text())
            selector=PREFIX+'EmulatorPublicImageReplayTest#productionGifDecoderAnimatesOriginalWhileGridUsesStaticThumbnail'
            for field,value in [('imageHashes',['a']),('postSettleSamples',2),('frames',[{'file':'../outside.png','accepted':True}]*3)]:
                changed=dict(original);changed[field]=value;path.write_text(json.dumps(changed))
                with self.subTest(field=field),self.assertRaises(AssertionError): contract.validate(directory,selector)
