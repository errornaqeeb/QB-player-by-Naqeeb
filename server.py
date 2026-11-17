"""
QB Player by Naqeeb - Local Streaming Server with HTTP Byte-Range (206) Support
Enables seamless video seeking, subtitle streaming, and offline playback.
"""

import os
import sys
import re
import mimetypes
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
import webbrowser

PORT = 8080
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class VideoStreamingHandler(BaseHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Range, Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        parsed_path = urllib.parse.urlparse(self.path).path
        if parsed_path in ('', '/'):
            parsed_path = '/index.html'

        # Sanitize path to prevent traversal
        safe_path = os.path.normpath(urllib.parse.unquote(parsed_path)).lstrip('\\/')
        file_path = os.path.join(DIRECTORY, safe_path)

        if not os.path.isfile(file_path):
            self.send_error(404, "File Not Found")
            return

        file_size = os.path.getsize(file_path)
        content_type, _ = mimetypes.guess_type(file_path)
        if not content_type:
            content_type = 'application/octet-stream'

        range_header = self.headers.get('Range')

        # Handle HTTP 206 Partial Content for video seeking
        if range_header:
            range_match = re.search(r'bytes=(\d+)-(\d*)', range_header)
            if range_match:
                start = int(range_match.group(1))
                end = int(range_match.group(2)) if range_match.group(2) else file_size - 1
                if start >= file_size:
                    self.send_response(416)
                    self.send_header('Content-Range', f'bytes */{file_size}')
                    self.end_headers()
                    return

                length = (end - start) + 1
                self.send_response(206)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Range', f'bytes {start}-{end}/{file_size}')
                self.send_header('Content-Length', str(length))
                self.send_header('Accept-Ranges', 'bytes')
                self.end_headers()

                with open(file_path, 'rb') as f:
                    f.seek(start)
                    bytes_to_send = length
                    while bytes_to_send > 0:
                        chunk_size = min(bytes_to_send, 64 * 1024)
                        data = f.read(chunk_size)
                        if not data:
                            break
                        self.wfile.write(data)
                        bytes_to_send -= len(data)
                return

        # Regular 200 OK delivery
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(file_size))
        self.send_header('Accept-Ranges', 'bytes')
        self.end_headers()

        with open(file_path, 'rb') as f:
            while True:
                chunk = f.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

def run():
    server_address = ('', PORT)
    try:
        httpd = HTTPServer(server_address, VideoStreamingHandler)
    except OSError:
        # Fallback to alternate port if 8080 is in use
        httpd = HTTPServer(('', 8899), VideoStreamingHandler)
        global PORT
        PORT = 8899

    print(f"==================================================")
    print(f"  QB Player by Naqeeb - Local Streaming Server")
    print(f"  URL: http://localhost:{PORT}")
    print(f"  100% Ad-Free Video Player Ready")
    print(f"==================================================")
    
    webbrowser.open(f"http://localhost:{PORT}")
    
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server...")
        httpd.server_close()

if __name__ == '__main__':
    run()
