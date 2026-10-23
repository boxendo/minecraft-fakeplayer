#!/usr/bin/env python3
"""Ensure the shaded release contains the current module classes, including incremental builds."""
from pathlib import Path
from zipfile import ZipFile
import sys
jar = Path(sys.argv[1])
count = 0
with ZipFile(jar) as archive:
    for module in ('fakeplayer-api', 'fakeplayer-core', 'fakeplayer-v26_3'):
        root = Path(module) / 'target/classes'
        for path in root.rglob('*.class'):
            name = path.relative_to(root).as_posix()
            assert archive.read(name) == path.read_bytes(), f'Stale/missing class: {name}'
            count += 1
print(f'PASS packaged classes match current compiler output: {count}')
