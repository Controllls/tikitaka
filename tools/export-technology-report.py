"""Export the implementation snapshot to local DOCX/HTML/PDF without changing app data."""
from pathlib import Path
import html
import json
import re
import sys
import argparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/reports/pleos-design-audit/deps'))
sys.path.insert(0, str(ROOT / 'build/reports/technology-inventory/deps'))
from docx import Document
from docx.shared import Mm, Pt
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from playwright.sync_api import sync_playwright
import pymupdf

parser = argparse.ArgumentParser()
parser.add_argument('--source', default='docs/IMPLEMENTED_TECHNOLOGY_2026-11-12.md')
parser.add_argument('--name', default='이동기록_구현기술과처리로직_2026-11-12')
parser.add_argument('--audit', default='build/reports/technology-inventory')
parser.add_argument('--checks', nargs='+', default=['SepFormer', 'Claude', '80', 'Figma', 'WorkManager', 'ECAPA', '96'])
args = parser.parse_args()
source = ROOT / args.source
out = Path('C:/Users/whdrm/Documents/현대_공모전')
name = args.name
audit = ROOT / args.audit
audit.mkdir(parents=True, exist_ok=True)
assert out.is_dir()
lines = source.read_text(encoding='utf-8').splitlines()
blocks = []
i = 0
while i < len(lines):
    line = lines[i]
    if not line.strip():
        i += 1
        continue
    if line.startswith('```'):
        code = []
        i += 1
        while i < len(lines) and not lines[i].startswith('```'):
            code.append(lines[i])
            i += 1
        blocks.append(('code', '\n'.join(code)))
    elif line.startswith('|'):
        rows = []
        while i < len(lines) and lines[i].startswith('|'):
            if not re.fullmatch(r'[|\s:-]+', lines[i]):
                rows.append([c.strip() for c in lines[i].strip('|').split('|')])
            i += 1
        blocks.append(('table', rows))
        continue
    elif line.startswith('#'):
        level = len(line) - len(line.lstrip('#'))
        blocks.append(('h' + str(level), line[level:].strip()))
    else:
        blocks.append(('p', line))
    i += 1

def plain(text):
    return text.replace('**', '').replace('`', '')

def inline(text):
    text = html.escape(text)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    return re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)

doc = Document()
section = doc.sections[0]
section.page_width, section.page_height = Mm(210), Mm(297)
section.top_margin = section.bottom_margin = Mm(18)
section.left_margin = section.right_margin = Mm(16)
for style_name in ['Normal', 'Title', 'Heading 1', 'Heading 2', 'Heading 3']:
    style = doc.styles[style_name]
    style.font.name = 'Malgun Gothic'
    style.element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'), '맑은 고딕')
doc.styles['Normal'].font.size = Pt(9)
doc.styles['Normal'].paragraph_format.space_after = Pt(6)
doc.styles['Normal'].paragraph_format.line_spacing = 1.2
parts = []
for kind, value in blocks:
    if kind == 'table':
        table = doc.add_table(rows=1, cols=len(value[0]))
        table.style = 'Light Shading Accent 1'
        for n, row in enumerate(value):
            cells = table.rows[0].cells if n == 0 else table.add_row().cells
            for cell, text in zip(cells, row):
                cell.text = plain(text)
            if n == 0:
                repeat = OxmlElement('w:tblHeader')
                table.rows[0]._tr.get_or_add_trPr().append(repeat)
        parts.append('<table><thead><tr>' + ''.join('<th>' + inline(c) + '</th>' for c in value[0]) +
                     '</tr></thead><tbody>' + ''.join('<tr>' + ''.join('<td>' + inline(c) + '</td>' for c in row) +
                     '</tr>' for row in value[1:]) + '</tbody></table>')
        doc.add_paragraph()
    elif kind.startswith('h'):
        level = int(kind[1:])
        doc.add_heading(plain(value), 0 if level == 1 else level - 1)
        parts.append(f'<{kind}>{inline(value)}</{kind}>')
    elif kind == 'code':
        paragraph = doc.add_paragraph(value)
        for run in paragraph.runs:
            run.font.size = Pt(6.5)
            run.font.name = 'Consolas'
        parts.append('<pre>' + html.escape(value) + '</pre>')
    else:
        doc.add_paragraph(plain(value))
        parts.append('<p>' + inline(value) + '</p>')
doc.core_properties.title = lines[0].lstrip('# ').strip()
doc.core_properties.subject = str(source.name)
doc.save(out / (name + '.docx'))
css = '''@page { size:A4; margin:18mm 16mm; }
body { font:10px/1.65 'Malgun Gothic',sans-serif; color:#1a293b; margin:0; }
h1 { font-size:25px; } h2 { font-size:17px; margin-top:25px; } h3 { font-size:13px; }
h1,h2,h3 { break-after:avoid; } p { margin:7px 0; overflow-wrap:anywhere; }
table { border-collapse:collapse; width:100%; margin:10px 0; table-layout:fixed; }
th,td { text-align:left; vertical-align:top; padding:7px; border:1px solid #d9e1ea; overflow-wrap:anywhere; }
th { background:#e8eef5; font-weight:700; } tr { break-inside:avoid; }
code { font-family:'Malgun Gothic',sans-serif; font-size:9px; }
pre { font:7px/1.65 Consolas,'Malgun Gothic',monospace; background:#f2f5f9; padding:8px; white-space:pre; }
'''
html_path = audit / 'report.html'
html_path.write_text('<!doctype html><html lang="ko"><meta charset="utf-8"><title>이동기록 구현 기술</title><style>' +
                     css + '</style><body>' + ''.join(parts) + '</body></html>', encoding='utf-8')
with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 672, 'height': 1000})
    page.goto(html_path.as_uri(), wait_until='load')
    page.evaluate('document.fonts.ready')
    overflow = page.evaluate("() => [...document.querySelectorAll('td,th,pre,p')].filter(e=>e.scrollWidth>e.clientWidth+2).map(e=>e.innerText.slice(0,80))")
    page.pdf(path=str(out / (name + '.pdf')), format='A4', print_background=True,
             prefer_css_page_size=True, display_header_footer=True, header_template='<div></div>',
             footer_template='<div style="font-size:8px;width:100%;text-align:center;color:#607085">HD_project · 2026-11-12 · <span class="pageNumber"></span> / <span class="totalPages"></span></div>')
    browser.close()
pdf = pymupdf.open(out / (name + '.pdf'))
pdf_text = '\n'.join(page.get_text() for page in pdf)
for needle in args.checks:
    assert needle in pdf_text, needle
for n in [0, min(7, len(pdf)-1), len(pdf)-1]:
    pdf[n].get_pixmap(matrix=pymupdf.Matrix(1.3, 1.3)).save(audit / f'page-{n+1}.png')
readback = Document(out / (name + '.docx'))
assert len(readback.tables) == sum(kind == 'table' for kind, _ in blocks)
info = dict(pages=len(pdf), tables=len(readback.tables), overflow=overflow,
            files=[str(out / (name + ext)) for ext in ('.docx', '.pdf')], source=str(source))
(audit / 'validation.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(info, ensure_ascii=False))
