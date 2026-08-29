#!/usr/bin/env bash
# Backend setup. Ubuntu 22.04, Python 3.10/3.11 (NOT 3.12), CUDA 12.1+.
set -euo pipefail

VDA_ROOT="${VDA_ROOT:-$HOME/Video-Depth-Anything}"

if [ ! -d "$VDA_ROOT" ]; then
  git clone https://github.com/DepthAnything/Video-Depth-Anything "$VDA_ROOT"
fi
cd "$VDA_ROOT"
bash get_weights.sh

# Never `pip install -r requirements.txt` from upstream: numpy==1.24.0 fails to build.
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
pip install "numpy<2" opencv-python imageio imageio-ffmpeg matplotlib easydict einops tqdm
pip install decord || pip install eva-decord
pip install fastapi "uvicorn[standard]" python-multipart "pydantic>=2"
pip install xformers || true   # optional; falls back to standard attention

python - <<'PY'
import torch, einops, tqdm, easydict, imageio, cv2
print("cuda:", torch.cuda.is_available(),
      torch.cuda.get_device_name(0) if torch.cuda.is_available() else "")
PY

# Real 1->100 progress (spec 3.6). Idempotent.
python "$(dirname "$0")/patch_progress.py" "$VDA_ROOT"

echo
echo "Setup done. Start the server with:"
echo "  export VDA_ROOT=$VDA_ROOT"
echo "  export DEPTHMAKER_TOKEN=<long-random-token>"
echo "  uvicorn server:app --host 0.0.0.0 --port 8000 --workers 1"
echo "(--workers 1 is mandatory: extra workers each try to own the GPU queue.)"
