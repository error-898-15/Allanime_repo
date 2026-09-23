#!/usr/bin/env python3
"""
plugin_update.py - CloudStream CS3 Plugin Catalog & Metadata Updater
Repository: error-898-15/Allanime_repo
Updates internal .cs3 zip manifests, computes SHA-256 hashes & file sizes,
and produces a 100% compliant plugins.json catalog for CloudStream 3.
"""

import os
import sys
import glob
import json
import zipfile
import hashlib

# Repository target for raw artifact links
REPO = os.environ.get("GITHUB_REPOSITORY", "error-898-15/Allanime_repo")

# Official Plugin Metadata (Includes AnimeDubHindi / Hindi Anime + all repo plugins)
PLUGIN_META = {
    'AnimeDubHindiPlugin': {
        'name': 'Anime Dub Hindi',
        'description': 'AnimeDubHindi - Watch Hindi, Tamil, Telugu, and Multi-Audio Anime & Movies with High-Speed Direct Cloud CDN (HubCloud & GDFlix)',
        'language': 'hi',
        'authors': ['error-898-15'],
        'tvTypes': ['Anime', 'AnimeMovie', 'Movie', 'TvSeries'],
        'iconUrl': 'https://www.animedubhindi.link/wp-content/uploads/2024/07/Untitled-design.png',
        'status': 1
    },
    'BlakitePlugin': {
        'name': 'Blakite Anime',
        'description': 'Blakite Provider - Stream Hindi Dubbed Anime & Cartoons directly via Blakite network',
        'language': 'hi',
        'authors': ['error-898-15'],
        'tvTypes': ['Anime', 'AnimeMovie', 'Cartoon'],
        'iconUrl': 'https://blogger.googleusercontent.com/img/a/AVvXsEgWJNM8v7dkKlHDuBncLOZsjiURJtbxv6de_W_TkIg75W51emlvr-3DATj02j__QUikkzjxhYKv8jYtQp4lc04xObvSTvthIHg_DA0Ud4SRiEUKqralljdfKnUumPN96NEBQwW6y0SpVKcCCPzuIwh8on5sgzjH7BT5PpR6_vp_qS7Qia8OMj04qz-DyMw=s937',
        'status': 1
    },
    'AnimeSaltPlugin': {
        'name': 'AnimeSalt',
        'description': 'AnimeSalt Provider - Watch high quality subbed and dubbed anime releases',
        'language': 'en',
        'authors': ['error-898-15'],
        'tvTypes': ['Anime', 'AnimeMovie', 'OVA'],
        'iconUrl': 'https://animesalt.cx/wp-content/uploads/cropped-AnimeSalticon-270x270.png',
        'status': 1
    },
    'GogoanimePlugin': {
        'name': 'Gogoanime',
        'description': 'Gogoanime Provider - Fast video streams for the latest seasonal anime episodes',
        'language': 'en',
        'authors': ['error-898-15'],
        'tvTypes': ['Anime', 'AnimeMovie', 'OVA'],
        'iconUrl': 'https://i3.wp.com/gogoanime.by/wp-content/uploads/2024/05/cropped-favicon-2-192x192.png',
        'status': 1
    },
    'YoutubePlugin': {
        'name': 'YouTube',
        'description': 'YouTube - Stream trending videos, music, gaming, news, and official channels directly on CloudStream',
        'language': 'en',
        'authors': ['error-898-15'],
        'tvTypes': ['Others', 'Live', 'TvSeries'],
        'iconUrl': 'https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/YouTube_full-color_icon_%282017%29.svg/512px-YouTube_full-color_icon_%282017%29.svg.png',
        'status': 1
    },
    'VidSrcPlugin': {
        'name': 'VidSrc',
        'description': 'VidSrc (https://vidsrc.sbs/) - Watch Movies & TV Series in HD with multi-server playback (Pro Multi, CineSrc, Videasy 4K, VidSrc)',
        'language': 'en',
        'authors': ['error-898-15'],
        'tvTypes': ['Movie', 'TvSeries'],
        'iconUrl': 'https://images2.imgbox.com/6c/fb/hHqTqE7b_o.png',
        'status': 1
    }
}

def update_cs3_artifacts():
    print("=== Scanning .cs3 plugin archives ===")
    cs3_files = glob.glob('**/*.cs3', recursive=True)
    if not cs3_files:
        print("No .cs3 files found in current scan.")
        return {}

    cs3_info = {}
    for cs3_path in cs3_files:
        base_name = os.path.basename(cs3_path)
        print(f"Processing archive: {base_name} ({cs3_path})")

        # 1. Update internal manifest.json within the .cs3 zip
        try:
            with zipfile.ZipFile(cs3_path, 'r') as zin:
                archive_files = {name: zin.read(name) for name in zin.namelist()}

            if 'manifest.json' in archive_files:
                manifest = json.loads(archive_files['manifest.json'].decode('utf-8'))
                internal_name = manifest.get('internalName', '')

                # Find matching meta
                meta = None
                for key, val in PLUGIN_META.items():
                    if key.lower() in base_name.lower() or internal_name.lower() == key.lower():
                        meta = val
                        break

                if meta:
                    manifest['name'] = meta['name']
                    manifest['description'] = meta['description']
                    manifest['iconUrl'] = meta['iconUrl']
                    manifest['language'] = meta.get('language', manifest.get('language', 'en'))
                    if 'tvTypes' in meta:
                        manifest['tvTypes'] = meta['tvTypes']

                    archive_files['manifest.json'] = json.dumps(manifest, indent=2).encode('utf-8')
                    with zipfile.ZipFile(cs3_path, 'w', zipfile.ZIP_DEFLATED) as zout:
                        for name, content in archive_files.items():
                            zout.writestr(name, content)
                    print(f"  [OK] Updated internal manifest for {meta['name']}")
        except Exception as e:
            print(f"  [WARN] Could not update inner manifest of {base_name}: {e}")

        # 2. Compute SHA-256 hash & file size
        with open(cs3_path, 'rb') as f:
            data = f.read()
        file_hash = 'sha256-' + hashlib.sha256(data).hexdigest()
        file_size = len(data)
        cs3_info[base_name] = {
            'hash': file_hash,
            'size': file_size,
            'path': cs3_path
        }
        print(f"  [HASH] {file_hash[:20]}... | SIZE: {file_size} bytes")

    return cs3_info

def sync_plugins_json(cs3_info):
    print("=== Syncing plugins.json catalog ===")
    targets = ['build/plugins.json', 'plugins.json']
    existing_catalog = []

    # Load existing catalog if available
    for p in targets:
        if os.path.exists(p):
            try:
                with open(p, 'r') as f:
                    existing_catalog = json.load(f)
                    print(f"Loaded existing plugins from {p} ({len(existing_catalog)} entries)")
                    break
            except Exception as e:
                print(f"Failed to read {p}: {e}")

    # Index existing catalog by internalName
    catalog_map = {}
    for item in existing_catalog:
        iname = item.get('internalName')
        if iname:
            catalog_map[iname] = item

    # Ensure all registered plugins in PLUGIN_META exist
    for plugin_key, meta in PLUGIN_META.items():
        base_cs3 = f"{plugin_key}.cs3"
        plugin_url = f"https://raw.githubusercontent.com/{REPO}/builds/{base_cs3}"

        item = catalog_map.get(plugin_key, {
            "name": meta['name'],
            "internalName": plugin_key,
            "version": 1,
            "url": plugin_url,
            "apiVersion": 1,
            "description": meta['description'],
            "authors": meta['authors'],
            "status": meta['status'],
            "tvTypes": meta['tvTypes'],
            "language": meta['language'],
            "iconUrl": meta['iconUrl']
        })

        # Apply metadata overrides
        item['name'] = meta['name']
        item['description'] = meta['description']
        item['iconUrl'] = meta['iconUrl']
        item['language'] = meta['language']
        item['tvTypes'] = meta['tvTypes']
        item['url'] = plugin_url

        # Apply hash and size if cs3 was built
        matched_cs3 = None
        for bname, info in cs3_info.items():
            if plugin_key.lower() in bname.lower():
                matched_cs3 = info
                break

        if matched_cs3:
            item['fileHash'] = matched_cs3['hash']
            item['fileSize'] = matched_cs3['size']

        catalog_map[plugin_key] = item

    final_list = list(catalog_map.values())

    # Write out to build/plugins.json and plugins.json
    os.makedirs('build', exist_ok=True)
    for p in targets:
        with open(p, 'w') as f:
            json.dump(final_list, f, indent=2)
        print(f"Wrote updated {p} with {len(final_list)} plugins.")

if __name__ == "__main__":
    info = update_cs3_artifacts()
    sync_plugins_json(info)
    print("=== plugin_update.py completed successfully ===")
