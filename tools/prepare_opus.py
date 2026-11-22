"""Prepare the vocabulary for the Xenova quantized OPUS-MT zh-en models."""
import json
import shutil
import struct
from pathlib import Path


def varint(data, position):
    value = 0
    shift = 0
    while True:
        part = data[position]
        position += 1
        value |= (part & 127) << shift
        if part < 128:
            return value, position
        shift += 7


def fields(data):
    position = 0
    while position < len(data):
        tag, position = varint(data, position)
        field, kind = divmod(tag, 8)
        if kind == 0:
            value, position = varint(data, position)
        else:
            if kind == 2:
                size, position = varint(data, position)
            else:
                size = {1: 8, 5: 4}[kind]
            value = data[position:position + size]
            position += size
        yield field, value


source = Path(".tools/opus")
assets = Path("app/src/main/assets/translation")
assets.mkdir(parents=True, exist_ok=True)
model = list(fields((source / "source.spm").read_bytes()))
trainer = dict(fields(next(value for tag, value in model if tag == 2)))
assert trainer.get(3, 1) == 1, "Expected a unigram SentencePiece model"
vocab = json.loads((source / "vocab.json").read_text(encoding="utf-8"))
assert (vocab["</s>"], vocab["<unk>"], vocab["<pad>"]) == (0, 1, 65000)
weights = {}
for tag, entry in model:
    if tag == 1:
        piece = dict(fields(entry))
        if piece.get(3, 1) in (1, 4):
            word = piece[1].decode()
            if word in vocab:
                weights[word] = struct.unpack("<f", piece[2])[0]

sorted_vocab = sorted(vocab, key=vocab.get)
assert all(vocab[piece] == index for index, piece in enumerate(sorted_vocab))
with (assets / "opus-vocab.bin").open("wb") as output:
    output.write(struct.pack(">i", len(sorted_vocab)))
    for piece in sorted_vocab:
        encoded = piece.encode("utf-8")
        output.write(struct.pack(">H", len(encoded)))
        output.write(encoded)
        output.write(struct.pack(">f", weights.get(piece, float("-inf"))))

for model in source.glob("*.onnx"):
    shutil.copyfile(model, assets / model.name)
