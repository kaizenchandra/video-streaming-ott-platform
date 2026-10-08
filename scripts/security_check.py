#!/usr/bin/env python3
"""Enforce the REST-only applicability boundary for CVE-2026-47884; no scanner suppression."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
count = 0
for source in (root / 'services').glob('*/src/main/java/**/*.java'):
    text = source.read_text()
    assert not any(name in text for name in ('XsltView', 'ModelAndView', 'ThymeleafViewResolver', 'JasperReportsView')), str(source)
    assert not re.search(r'@(?:Request|Get|Post)Mapping\([^)]*"/\*\*"', text), str(source)
    if 'adapter/inbound/rest' in str(source) and '@Controller' in text:
        raise AssertionError(f'View controller forbidden: {source}')
    if '@RestController' in text:
        count += 1
print(f'PASS REST-only security boundary: {count} controllers; no XSLT/implicit rendering')
