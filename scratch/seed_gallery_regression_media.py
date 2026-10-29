from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import subprocess
import json
import shutil
import random
import os

root = Path('scratch/gallery-regression-media-20261020')
root.mkdir(exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf', 32)
albums = [('QA 01 Small', 4), ('QA 02 Mixed', 12), ('QA 03 Large', 96),
          ('QA 04 Empty looking white images', 3), ('QA 05 Portrait', 10), ('QA 06 Wide', 10),
          ('QA 07 中文相册', 12), ('QA 08 Very long album name for layout overflow checking 2026', 8),
          ('QA 09 Same filenames A', 6), ('QA 10 Same filenames B', 6),
          ('QA 11 Older dates', 12), ('QA 12 Recent dates', 12)]
albums += [(f'QA {n:02} Scroll album', 8 + n % 5) for n in range(13, 25)]
extensions = ['jpg', 'png', 'webp', 'gif']
manifest = []
for ai, (album, count) in enumerate(albums):
    directory = root / album
    directory.mkdir(exist_ok=True)
    for i in range(count):
        ext = extensions[i % 4] if ai != 0 else 'jpg'
        size = [(640, 480), (360, 720), (1024, 256), (128, 128)][(i + ai) % 4]
        if ai == 4: size = (360, 720)
        if ai == 5: size = (1024, 256)
        rng = random.Random(ai * 1000 + i)
        color = tuple(rng.randrange(40, 210) for _ in range(3))
        image = Image.new('RGB', size, 'white' if ai == 3 else color)
        draw = ImageDraw.Draw(image)
        draw.ellipse((size[0] // 8, size[1] // 8, size[0] * 7 // 8, size[1] * 7 // 8), fill=tuple(255-c for c in color))
        draw.rectangle((8, 8, min(size[0]-8, 420), min(size[1]-8, 92)), fill='#202030')
        draw.text((16, 16), f'Album {ai+1:02} / {i+1:03}', font=font, fill='white')
        name = f'photo-{i+1:03}.{ext}'
        if ai == 7: name = f'long-filename-for-layout-and-search-testing-{i+1:03}.{ext}'
        target = directory / name
        if ext == 'gif':
            frame = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            image.save(target, save_all=True, append_images=[frame], duration=350, loop=0)
        else: image.save(target)
        if ai == 10:
            timestamp = 1580515200 + i * 86400 * 20
            os.utime(target, (timestamp, timestamp))
        manifest.append(str(target.relative_to(root)))

# Byte-identical copies and same filenames with different bytes both remain available for testing.
shutil.copy2(root / albums[8][0] / 'photo-001.jpg', root / albums[9][0] / 'photo-001.jpg')
ffmpeg = 'D:/Program Files/FFmpeg/bin/ffmpeg.exe'
for i in range(4):
    directory = root / albums[1 if i < 2 else 2][0]
    target = directory / f'video-{i+1:02}.mp4'
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
                    f'testsrc2=size={"640x360" if i%2==0 else "360x640"}:rate=12', '-t', '2',
                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(target)], check=True)
    manifest.append(str(target.relative_to(root)))
subprocess.run(['D:/Android_Studio_Sdk/platform-tools/adb.exe', 'push', str(root), '/sdcard/Pictures/GalleryQA20261020'], check=True)
report = {'albums': len(albums), 'media': len(manifest), 'remote_root': '/sdcard/Pictures/GalleryQA20261020',
          'album_counts': {name: count + (2 if index in (1,2) else 0) for index,(name,count) in enumerate(albums)},
          'formats': ['JPEG','PNG','WebP','animated GIF','MP4'], 'files': manifest}
Path('scratch/gallery-regression-media-manifest.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps({key: report[key] for key in ['albums','media','remote_root','formats']}))
