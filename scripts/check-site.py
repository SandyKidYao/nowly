#!/usr/bin/env python3
"""Check local assets, links and current documentation before publishing."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re

root = Path(__file__).resolve().parents[1]


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.refs = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if 'id' in values:
            assert values['id'] not in self.ids, f'Duplicate id: {values["id"]}'
            self.ids.add(values['id'])
        for name in ('src', 'href'):
            if name in values:
                self.refs.append(values[name])
        if tag == 'img':
            assert 'alt' in values, 'Image needs alt text'


page = Page()
page.feed((root / 'site/index.html').read_text())
for ref in page.refs:
    url = urlsplit(ref)
    if url.scheme or url.netloc:
        continue
    if url.path:
        assert (root / 'site' / unquote(url.path)).exists(), f'Missing site file: {ref}'
    if url.fragment and not url.path:
        assert url.fragment in page.ids, f'Missing anchor: {ref}'
for doc in [root / 'README.md', *sorted((root / 'docs').glob('*.md'))]:
    for ref in re.findall(r'\]\(([^)]+)\)', doc.read_text()):
        url = urlsplit(ref)
        if not url.scheme and url.path:
            assert (doc.parent / unquote(url.path)).exists(), f'{doc.name}: broken link {ref}'
print('Site assets, anchors, image descriptions and documentation links are valid.')
