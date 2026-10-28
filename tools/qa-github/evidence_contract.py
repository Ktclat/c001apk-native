"""Minimum new-original contracts; missing/partial artifacts never qualify a runner PASS."""
import hashlib,json,re
from pathlib import Path

EMOTE={
'actualFeedTitleBodyAndHotReplyUseBundledArt':['title','preview-known-local-img-visible-after-scroll','hot-reference','reader-known-local-img-visible-after-scroll','ordinary-reply','nested-reply'],
'darkLargeFontHtmlLocalImageOccurrenceRemainsVisible':['dark-title','preview-known-local-img-visible-after-scroll','reader-known-local-img-visible-after-scroll','ordinary-reply','nested-reply'],
'darkLargeFontFeedAndDetailRemainReadable':['dark-markdown-preview','dark-markdown-reader','ordinary-reply','nested-reply'],
'directPointerOnLinkedEmoteDoesNotActivateParentFeedCard':['visible-linked-glyph','linked-target'],
'actualSettingsOffOnChangesRenderingWithoutChangingFeedSource':['preview-known-local-img-visible-after-scroll','memory-settings-off','memory-off-literal']}
MEDIA={
'actualCarouselManualRetryDoesNotOpenItsParentAndDiagnosticsAreBounded':['decode-error','retry-loaded','diagnostics-scope','diagnostics-visible-groups'],
'blockedBannerStopsAfterOneRetryAndGallery404StaysExplicit':['blocked-initial','blocked-terminal','gallery404','gallery404-after-explicit-retry'],
'darkLargeFontRetryIsOnlyShownWhenGlyphAndTouchBoundsFit':['dark-large-retry-contained','dark-large-terminal'],
'productionGifDecoderAnimatesOriginalWhileGridUsesStaticThumbnail':['static-grid','after-normal-back-static']}
FIXED={
'actualPersonalFollowUserEntityAndBackStayReadOnly':('personal-follow',['loaded-user-entity','user1002-destination','back-personal-follow'],['personal-follow-contract.txt','personal-follow-image-requests.txt']),
'actualReply500AnchorSurvivesCachedPrependWhileChild43IsOpen':('anchor',['reply500-before','child43','reply500-after','page3-retained'],['anchor-retention.txt']),
'actualSystemDropdownOpens501Feed502AndBackRetainsUnsupportedRowPolicy':('notification',['mentions','system-before','feed502','back-retained','unsupported-stays-native'],['notification-retained.txt']),
'fourLiteralSummaryPathsKeepArtOverflowParentRoutesAndReadOnlyMessages':('literal-summary',['home-message-literal','local-summary-eight-line-ellipsis','local-parent-feed7','unsupported-notice-no-navigation','private-summary-literal-html','conversation-read-only','conversation-back'],[])}

def validate(directory,selector):
    directory=Path(directory);method=selector.split('#',1)[1];required=[];captures=[]
    if 'EmulatorAnimationEvidenceWindowTest#' in selector: return [] # raw start/terminal only
    if method in EMOTE:
        prefix='emotes-'+method;captures=[prefix+'-'+s for s in EMOTE[method]];required+=[prefix+'-requests.txt']
    elif method in MEDIA:
        prefix='media-'+method;captures=[prefix+'-'+s for s in MEDIA[method]];required+=[prefix+'-requests.txt']
    elif method in FIXED:
        prefix,stages,extra=FIXED[method];captures=[prefix+'-'+s for s in stages];required += [prefix+'-requests.txt']+extra
    elif method=='actualEmotePreferenceToggleSurvivesReentryAndRecreation':
        captures=['production-emotes-'+suffix for suffix in ('01-off','02-reentry-off','03-recreated-off','04-on')]
        required+=['production-emotes-persistence.txt','production-emotes-restored.txt']
    elif method=='fullReaderRawAndActualCopyPreserveOriginalTokensAndCode':
        for stage in ('formatted','raw'):
            captures+=['copy-'+stage+'-toolbar-original']
            required += ['copy-'+stage+suffix for suffix in ('-sync.txt','-toolbar.xml','-toolbar-windowmanager.txt','-exact.txt','-receipt.txt','-copy-action.txt','-select-all-action.txt')]
        required+=['emotes-'+method+'-requests.txt']
    elif method in ('defaultHttpsIsRejectedBeforeTls','productionMediaIsRejectedBeforeTls'):
        required+=['boundary-'+('default-https' if method.startswith('default') else 'production-media')+'.txt']
    else: raise AssertionError('No evidence contract for selector')
    for capture in captures: required += [capture+suffix for suffix in ('.png','.txt','-capture-trace.txt')]
    if method=='productionGifDecoderAnimatesOriginalWhileGridUsesStaticThumbnail':
        required+=['gif-source-provenance.json','gif-before-temporal.txt','gif-temporal-trace.json']
        trace=json.loads((directory/'gif-temporal-trace.json').read_text())
        assert trace['settleMs']==1500 and trace['samplingBudgetMs']==12000 and trace['postSettleSamples']>=3 and len(trace['imageHashes'])>=2
        assert trace['sourceFrameCount']>1
        frames=[row['file'] for row in trace['frames'] if 'file' in row]
        assert all(isinstance(name,str) and re.fullmatch(r'gif-original-[0-9]{2,}\.png',name) for name in frames), 'Unsafe or unexpected frame basename'
        assert len(frames)==len(set(frames)), 'Repeated frame file'
        assert len(frames)>=3 and any(row.get('accepted') for row in trace['frames'])
        required+=frames
    for name in required:
        path=directory/name
        assert path.is_file() and path.stat().st_size>0, 'Missing required original: '+name
        if path.suffix=='.png': assert path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid PNG: '+name
    if method=='fullReaderRawAndActualCopyPreserveOriginalTokensAndCode':
        for stage,length,digest in [('formatted',97,'a9ad7ccd576cb2959cda8141b71276b608f90a45b8007e90e4451b1aa0cd385c'),('raw',117,'fc14b342f4089d5c62306d31546267f2a1dfeb932815cc3ff6cd10f3df1884f8')]:
            data=(directory/('copy-'+stage+'-exact.txt')).read_bytes()
            assert hashlib.sha256(data).hexdigest()==digest and len(data.decode().encode('utf-16-le'))//2==length
    return required
