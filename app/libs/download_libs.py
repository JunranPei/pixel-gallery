import urllib.request
import os
import ssl

# Bypass SSL certificate verification if needed
ssl._create_default_https_context = ssl._create_unverified_context

libs_dir = r"D:\workplace\antigravity\pixel-gallery\app\libs"
if not os.path.exists(libs_dir):
    os.makedirs(libs_dir)

files = {
    "Android-TiffBitmapFactory-424b18a4ae.aar": "https://jitpack.net/com/github/deckerst/Android-TiffBitmapFactory/424b18a4ae/Android-TiffBitmapFactory-424b18a4ae.aar",
    "androidsvg-c7e58e8e59.aar": "https://jitpack.net/com/github/deckerst/androidsvg/c7e58e8e59/androidsvg-c7e58e8e59.aar"
}

for name, url in files.items():
    dest = os.path.join(libs_dir, name)
    print(f"Downloading {url} to {dest}...")
    try:
        urllib.request.urlretrieve(url, dest)
        print(f"Successfully downloaded {name}")
    except Exception as e:
        print(f"Failed to download {name}: {e}")
