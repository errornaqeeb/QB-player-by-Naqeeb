"""
Helper script to copy web assets into the Android assets directory.
"""
import shutil
import os

def sync():
    root = os.path.dirname(os.path.abspath(__file__))
    dest_dir = os.path.join(root, 'android', 'app', 'src', 'main', 'assets')
    os.makedirs(dest_dir, exist_ok=True)

    for item in ['index.html', 'manifest.json', 'service-worker.js']:
        src = os.path.join(root, item)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(dest_dir, item))

    for folder in ['css', 'js', 'assets']:
        src_folder = os.path.join(root, folder)
        dest_folder = os.path.join(dest_dir, folder)
        if os.path.exists(dest_folder):
            shutil.rmtree(dest_folder)
        if os.path.exists(src_folder):
            shutil.copytree(src_folder, dest_folder)

    print('[QB Player] Web assets synced to Android project assets successfully.')

if __name__ == '__main__':
    sync()
