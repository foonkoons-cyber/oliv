"""
Apply the real 1->100 progress patch to the upstream model (spec 3.6).

infer_video_depth gives no progress signal, and the owner requires a real
counter — not a timer-driven animation that desyncs on clips of different
length. The window loop upstream is already wrapped in tqdm, so this patch
shims tqdm inside that one module and routes each completed window to a
callback.

Idempotent: run it as many times as you like.

    python patch_progress.py [/path/to/Video-Depth-Anything]
"""
from __future__ import annotations

import os
import re
import sys

MARKER = "# --- DepthMaker progress patch ---"

SHIM = f'''
{MARKER}
import contextvars as _dm_ctx

_dm_progress = _dm_ctx.ContextVar("dm_progress", default=None)
_dm_tqdm_orig = tqdm


def tqdm(iterable=None, *args, **kwargs):   # noqa: F811 - deliberate shim
    """Same tqdm, plus a per-iteration callback when one is installed."""
    _cb = _dm_progress.get()
    _it = _dm_tqdm_orig(iterable, *args, **kwargs)
    if _cb is None or iterable is None:
        return _it

    try:
        _total = len(iterable)
    except Exception:
        _total = None

    def _wrapped():
        for _i, _item in enumerate(_it):
            yield _item
            if _total:
                try:
                    _cb(_i + 1, _total)
                except Exception:
                    pass

    return _wrapped()
# --- end DepthMaker progress patch ---
'''


def patch(repo_root: str) -> int:
    target = os.path.join(repo_root, "video_depth_anything", "video_depth.py")
    if not os.path.exists(target):
        print(f"ERROR: {target} not found — is VDA_ROOT correct?")
        return 1

    src = open(target).read()
    if MARKER in src:
        print("already patched — nothing to do")
        return 0

    if "tqdm" not in src:
        print("ERROR: no tqdm in video_depth.py; the upstream loop shape changed.")
        print("Patch it by hand (spec 3.6) — do NOT ship a fake progress bar.")
        return 2

    # 1. insert the shim after the import block
    lines = src.splitlines(keepends=True)
    last_import = 0
    for i, line in enumerate(lines[:80]):
        if re.match(r"^\s*(import|from)\s+\S", line):
            last_import = i
    lines.insert(last_import + 1, SHIM)
    src = "".join(lines)

    # 2. add the keyword argument to infer_video_depth
    m = re.search(r"def\s+infer_video_depth\s*\(", src)
    if not m:
        print("ERROR: infer_video_depth not found")
        return 3
    depth, i = 0, m.end() - 1
    while i < len(src):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    close = i
    if "progress_callback" not in src[m.start():close]:
        src = src[:close] + ", progress_callback=None" + src[close:]

    # 3. install the callback as the first statement of the body
    body_start = src.index(":", close) + 1
    nl = src.index("\n", body_start) + 1
    indent_match = re.match(r"[ \t]*", src[nl:])
    rest = src[nl:]
    first_line = rest.split("\n", 1)[0]
    indent = re.match(r"[ \t]*", first_line).group(0) or "        "
    # keep a docstring first if there is one
    stripped = first_line.strip()
    if stripped.startswith(('"""', "'''")):
        quote = stripped[:3]
        end = rest.index(quote, rest.index(quote) + 3) + 3
        insert_at = nl + rest.index("\n", end) + 1
    else:
        insert_at = nl
    src = src[:insert_at] + f"{indent}_dm_progress.set(progress_callback)\n" + src[insert_at:]

    backup = target + ".orig"
    if not os.path.exists(backup):
        open(backup, "w").write(open(target).read())
    open(target, "w").write(src)
    print(f"patched {target} (backup at {backup})")
    print("verify:  python -c \"import inspect, video_depth_anything.video_depth as m;"
          " print('progress_callback' in inspect.signature(m.VideoDepthAnything.infer_video_depth).parameters)\"")
    return 0


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
        "VDA_ROOT", os.path.expanduser("~/Video-Depth-Anything")
    )
    sys.exit(patch(root))
