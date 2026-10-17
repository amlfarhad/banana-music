#!/usr/bin/env python3
"""Serve only the Banana Music APK to devices on this Mac's local network."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import argparse

APK = Path(__file__).resolve().parent.parent / 'downloads/Banana-Music-0.2.1.apk'
PAGE = b'''<!doctype html><html><meta name="viewport" content="width=device-width">
<title>Banana Music download</title><body style="background:#121216;color:white;font:24px sans-serif;padding:48px">
<h1>Banana Music</h1><p>Apple Music companion for Android TV.</p>
<p><a style="color:#ff7a96" href="/app.apk">Download Banana Music APK</a></p>
<p>After downloading, choose Install.</p></body></html>'''

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/':
            data, mime = PAGE, 'text/html; charset=utf-8'
        elif self.path == '/app.apk':
            data, mime = APK.read_bytes(), 'application/vnd.android.package-archive'
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(data)))
        if self.path == '/app.apk':
            self.send_header('Content-Disposition', 'attachment; filename="Banana-Music-0.2.1.apk"')
        self.end_headers()
        self.wfile.write(data)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', required=True)
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    print(f'TV download: http://{args.bind}:{args.port}', flush=True)
    HTTPServer((args.bind, args.port), Handler).serve_forever()
