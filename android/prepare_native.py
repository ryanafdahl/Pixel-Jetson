"""Fetch the exact public LiteRT C headers used by the pinned 2.2.0 runtime."""
from pathlib import Path
import re
import urllib.request

root = Path(__file__).resolve().parent / 'build/native/include'
pending = ['litert/c/litert_environment.h', 'litert/c/litert_model.h', 'litert/c/litert_compiled_model.h',
           'litert/c/litert_tensor_buffer.h', 'litert/c/litert_options.h', 'litert/c/litert_opaque_options.h',
           'litert/c/options/litert_google_tensor_options_type.h']
seen = set()
while pending:
    name = pending.pop()
    if name in seen:
        continue
    seen.add(name)
    dest = root / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    url_name = name + '.in' if name == 'litert/build_common/build_config.h' else name
    print(url_name, flush=True)
    source = urllib.request.urlopen('https://raw.githubusercontent.com/google-ai-edge/LiteRT/v2.2.0/' + url_name, timeout=30).read()
    if url_name.endswith('.in'):
        source = re.sub(rb'#cmakedefine01 (\w+)', rb'#define \1 0', source)
    dest.write_bytes(source)
    pending.extend(re.findall(r'#include "(litert/[^\"]+)"', source.decode()))
print('Fetched', len(seen), 'version-matched public headers')
