#!/usr/bin/env python3
"""New geometric fixture art only; no downloaded/CDN/user image and no historical-byte claim."""
from pathlib import Path
import hashlib,json,sys
from PIL import Image,ImageDraw,__version__
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
frames=[]
for background,rect in [((30,75,220),(20,60,120,180)),((20,160,70),(240,60,340,180))]:
    im=Image.new('RGB',(360,240),background)
    ImageDraw.Draw(im).rectangle(rect,fill=(250,220,20));frames.append(im)
frames[0].save(out/'static.png',format='PNG')
frames[0].save(out/'animated.gif',format='GIF',save_all=True,append_images=frames[1:],duration=400,loop=0,disposal=2,optimize=False)
files=[]
for name in ['static.png','animated.gif']:
    p=out/name; data=p.read_bytes()
    with Image.open(p) as im:
        count=getattr(im,'n_frames',1);durations=[]
        for index in range(count): im.seek(index);durations.append(im.info.get('duration',0))
        files.append({'file':name,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'width':im.width,'height':im.height,'frameCount':count,'durationsMs':durations,'format':im.format})
assert files[1]['frameCount']==2 and len(set(hashlib.sha256(f.tobytes()).hexdigest() for f in frames))==2
(out/'provenance.json').write_text(json.dumps({'scope':'New programmatic synthetic geometric fixtures; not original lost bytes, CDN art, user upload, runtime output or restoration proof','generator':'generate-synthetic-media.py','pillow':__version__,'files':files},indent=2)+'\n')
print((out/'provenance.json').read_text())
