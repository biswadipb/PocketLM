#!/bin/sh
# Builds voice_host against a Mac build of third_party/llama.cpp made in $BUILD (see engine/README or the commit message),
# then runs it. Example:
#   BUILD=/tmp/llama-host ./run.sh model.gguf mmproj.gguf "Hello there." out.wav
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../../.." && pwd)
LLAMA=$ROOT/third_party/llama.cpp
BUILD=${BUILD:?set BUILD to the folder where llama.cpp was built for this computer}
clang++ -std=c++17 -O2 "$HERE/voice_host.cpp" \
  -I"$ROOT/engine/src/main/cpp" -I"$LLAMA" -I"$LLAMA/common" -I"$LLAMA/include" -I"$LLAMA/ggml/include" -I"$LLAMA/tools/mtmd" \
  -L"$BUILD/bin" -lllama -lllama-common -lmtmd -lggml -lggml-base -Wl,-rpath,"$BUILD/bin" -o "$BUILD/voice_host"
"$BUILD/voice_host" "$@"
