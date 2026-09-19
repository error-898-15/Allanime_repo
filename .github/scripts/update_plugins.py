import json
import glob
import zipfile
import os
import hashlib

PLUGIN_META = {
            'BlakitePlugin': {
                'name': 'Blakite Anime',
                'iconUrl': 'https://raw.githubusercontent.com/error-898-15/Uchiharepo/builds/icons/BlakitePlugin.png'
            },
            'ZLivePlugin': {
                'name': 'Z-Live',
                'iconUrl': 'https://raw.githubusercontent.com/error-898-15/Uchiharepo/builds/icons/ZLivePlugin.png'
            },
            'AnimeSaltPlugin': {
                'name': 'Anime Salt',
                'iconUrl': 'https://raw.githubusercontent.com/error-898-15/Uchiharepo/builds/icons/AnimeSaltPlugin.png'
            },
            'RareAnimesPlugin': {
                'name': 'RareAnimes',
                'iconUrl': 'https://raw.githubusercontent.com/error-898-15/Uchiharepo/builds/icons/RareAnimesPlugin.png'
            },
            'NetMirrorTVPlugin': {
                'name': 'NetMirrorTV',
                'iconUrl': 'https://raw.githubusercontent.com/error-898-15/Uchiharepo/builds/icons/NetMirrorTVPlugin.png'
            },
            'FlixVisionPlugin': {
                'name': 'FlixVision',
                'iconUrl': 'https://raw.githubusercontent.com/error-898-15/Uchiharepo/builds/icons/FlixVisionPlugin.png'
            },
            'CinevoodPlugin': {
                'name': 'CineVood',
                'iconUrl': 'https://cinevood.rocks/wp-content/uploads/2020/07/favicon2.png'
            }
}
cs3_hashes = {}
for cs3_file in glob.glob('**/*.cs3', recursive=True):
    base = os.path.basename(cs3_file)
    try:
        with zipfile.ZipFile(cs3_file, 'r') as zin:
            items = {name: zin.read(name) for name in zin.namelist()}
        if 'manifest.json' in items:
            manifest = json.loads(items['manifest.json'].decode('utf-8'))
            for plugin_key, meta in PLUGIN_META.items():
                if plugin_key.lower() in base.lower() or manifest.get('internalName', '').lower() == plugin_key.lower():
                    manifest['name'] = meta['name']
                    if 'iconUrl' in meta:
                        manifest['iconUrl'] = meta['iconUrl']
            items['manifest.json'] = json.dumps(manifest, indent=2).encode('utf-8')
            with zipfile.ZipFile(cs3_file, 'w', zipfile.ZIP_DEFLATED) as zout:
                for name, data in items.items():
                    zout.writestr(name, data)
    except Exception as e:
        print(f"Warning processing {cs3_file}: {e}")

    try:
        with open(cs3_file, 'rb') as f:
            content = f.read()
        h = 'sha256-' + hashlib.sha256(content).hexdigest()
        sz = len(content)
        cs3_hashes[base] = (h, sz)
    except Exception as e:
        print(f"Warning hashing {cs3_file}: {e}")

for pjson in ['build/plugins.json', 'plugins.json']:
    if os.path.exists(pjson):
        try:
            with open(pjson, 'r') as f:
                data = json.load(f)
            if isinstance(data, list):
                for item in data:
                    iname = item.get('internalName', '')
                    for plugin_key, meta in PLUGIN_META.items():
                        if iname.lower() == plugin_key.lower() or plugin_key.lower() in item.get('url', '').lower():
                            item['name'] = meta['name']
                            item['iconUrl'] = meta['iconUrl']
                    for base, (h, sz) in cs3_hashes.items():
                        if base in item.get('url', '') or iname in base:
                            item['fileHash'] = h
                            item['fileSize'] = sz
                with open(pjson, 'w') as f:
                    json.dump(data, f, indent=2)
                print(f"Updated {pjson} with metadata and hashes.")
        except Exception as e:
            print(f"Error updating {pjson}: {e}")

print("Plugin metadata & hashes verification complete.")
