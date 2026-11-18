# SoundDrop protocol, version 1

This document specifies how SoundDrop turns bytes into sound and back. Everything described here is
implemented in the `modem` module (pure common Kotlin); the constants live in
`modem/src/commonMain/kotlin/dev/sounddrop/modem/protocol/`.

All timing is defined in **seconds**, never in samples. A sender and a receiver may run at different
sample rates (typically 44.1 kHz and 48 kHz); each converts the times below to its own sample counts
with `round(seconds × sampleRate)` (`FrameLayout`).

## 1. Frame structure

```
| chirp 200 ms | gap 30 ms | header: 7 symbols (Robust) | payload: n symbols (any profile) | tail 30 ms |
```

| Part | Duration | Purpose |
|---|---|---|
| Preamble | 200 ms | Linear chirp for detection and timing |
| Gap | 30 ms | Silence so the chirp's room echo fades before the first symbol |
| Header | 7 × 64 ms = 448 ms | Version, profile, payload type and length; always sent with Robust timing |
| Payload | n × symbol time | Data + CRC-32 + Reed–Solomon parity |
| Tail | 30 ms | Short silence |

Example: a 26-byte text message in Robust mode takes 0.23 + 0.448 + 46 × 0.064 + 0.03 ≈ **3.7 s**.

## 2. Preamble

* Linear chirp from **1500 Hz to 5000 Hz** over 200 ms:
  `x(t) = A · w(t) · sin(2π (f₀ t + (f₁ − f₀) t² / (2T)))`.
* `w(t)` is a Tukey window with 10 % tapering (5 % raised-cosine ramp at each end). Peak amplitude 0.9.

**Detection** (`PreambleDetector`): the receiver cross-correlates the incoming audio with the chirp
template using FFT overlap-save (block size = next power of two ≥ 2 × template length). The correlation
is normalised by the energy of both the template and the signal window:

```
score(l) = |Σ x[l+i] · t[i]| / sqrt(Σ t[i]² · Σ x[l+i]²)      (0 ≤ score ≤ 1)
```

A preamble is accepted when the score exceeds **0.3**. The start is the maximum score within half a
template length after the first threshold crossing. Because the score is normalised, loudness does not
matter; because a chirp's autocorrelation is very narrow, the timing is accurate to about one sample.
The absolute value tolerates speakers or microphones that invert polarity.

False detections are filtered by the header, which must pass Reed–Solomon decoding and validity checks.

## 3. Symbols

Each symbol carries **one byte**: the high nibble selects one of 16 tones in band A, the low nibble one
of 16 tones in band B. Both tones play at the same time.

| | Band A (high nibble) | Band B (low nibble) |
|---|---|---|
| Tone k frequency | 1875 + 93.75 · k Hz | 3468.75 + 93.75 · k Hz |
| Range | 1875 – 3281.25 Hz | 3468.75 – 4875 Hz |

* **Gray code:** the nibble value `v` is sent on tone `k` with `gray(k) = k xor (k >> 1) = v`, so mistaking
  a tone for its neighbour flips only one bit.
* **Waveform:** two phase-continuous oscillators (one per band), each with amplitude 0.45, so the sum
  never exceeds 0.9. Changing frequency at a symbol boundary keeps the phase, so there are no clicks.
  The whole symbol section fades in and out with 4 ms raised-cosine ramps.

### Profiles

| Profile | Guard | Analysis window | Symbol | Raw rate |
|---|---|---|---|---|
| Robust (id 0) | 21.33 ms | 42.67 ms | 64 ms | 125 bit/s |
| Fast (id 1) | 10.67 ms | 21.33 ms | 32 ms | 250 bit/s |
| Melodic (id 2) | 20 ms | 60 ms | 2 × 80 ms = 160 ms | 50 bit/s |

The receiver ignores the **guard** at the start of every symbol, which absorbs reverberation of the
previous symbol, and analyses only the **window**. The tone spacing of 93.75 Hz is exactly 4 / window
(Robust) and 2 / window (Fast), so tones are orthogonal under the Hann window the receiver applies.

### Melodic profile

In Melodic mode a symbol is still one byte, but it is played as **two notes in a row** instead of a
chord: the high nibble first, then the low nibble, each in its own 80 ms slot with its own guard and
window. Both nibbles use the same 16 notes, three octaves of the C major pentatonic scale:

| Tone k | 0 | 1 | 2 | 3 | 4 | 5 | … | 14 | 15 |
|---|---|---|---|---|---|---|---|---|---|
| Note | C5 | D5 | E5 | G5 | A5 | C6 | … | A7 | C8 |
| Frequency | 523.3 Hz | 587.3 Hz | 659.3 Hz | 784.0 Hz | 880.0 Hz | 1046.5 Hz | … | 3520 Hz | 4186 Hz |

`f(k) = 523.2511 · 2^(s/12)` with `s = 12 · (k div 5) + [0, 2, 4, 7, 9][k mod 5]` (equal temperament,
A4 = 440 Hz). The scale has no semitone steps, so any sequence of notes sounds consonant. The closest
notes (C5/D5) are 64 Hz apart, about four bins of the 60 ms window, so the tones are not orthogonal
but leak less than −30 dB into each other.

Every note is plucked: a 3 ms raised-cosine attack, an exponential decay with a 150 ms time constant
and a 10 ms release, peak amplitude 0.9. Because each note starts and ends at zero, the phase does not
need to be continuous. The Robust header before it is faded out at its end.

Gray coding, demodulation, erasures and error correction are the same as for the chord profiles; in
`SymbolReading`, band A means the first note and band B the second.

### Demodulation

For each symbol the receiver (`SymbolDemodulator`) measures the power of all 32 tones with **Goertzel
filters at the exact tone frequencies** over the Hann-windowed analysis window, and picks the strongest
tone per band. Because Goertzel works at arbitrary frequencies, no resampling is needed between 44.1 kHz
and 48 kHz devices.

**Confidence** is the power ratio between the strongest and second-strongest tone, taking the weaker of
the two bands. Symbols with a confidence below **2** are passed to the decoder as *erasures*.

## 4. Header

Three data bytes plus four Reed–Solomon parity bytes, always with Robust timing:

| Byte | Content |
|---|---|
| 0 | `version << 4 \| profileId` (version = 1) |
| 1 | payload type |
| 2 | payload length in bytes (0 – 160) |
| 3 – 6 | RS parity, corrects up to 2 byte errors |

A header is rejected if decoding fails, the version is unknown, the profile id is unknown or the length
exceeds 160. The receiver then resumes the preamble search half a chirp length after the false peak.

## 5. Payload

```
payload bytes (n) | CRC-32 (4 bytes, big-endian) | RS parity (p bytes)
```

* **CRC-32**: IEEE 802.3 (reflected polynomial 0xEDB88320, as in zip and PNG) over the payload bytes.
* **Parity**: `p = max(8, 2 · ceil((n + 4) / 4))`, about 50 % redundancy. With p parity bytes the
  decoder corrects any combination of `e` errors and `s` erasures with `2e + s ≤ p`.
* The largest payload (160 bytes) gives 160 + 4 + 82 = 246 symbols, which fits in one RS codeword
  (≤ 255 bytes).

## 6. Reed–Solomon code

Systematic RS over **GF(2⁸)** with primitive polynomial **x⁸ + x⁴ + x³ + x² + 1 (0x11D)**, generator
α = 2, and generator polynomial roots α⁰ … α^(p−1). Codewords may be shortened to any length up to 255.
Byte `k` of an `N`-byte codeword is the coefficient of `x^(N−1−k)`.

Decoding (`ReedSolomon.decode`):

1. Syndromes `S_j = r(α^j)`.
2. Erasure locator `Γ(x) = Π (1 − α^(N−1−k) x)` over the erased positions.
3. Berlekamp–Massey initialised with `Γ` gives the combined errata locator `Λ(x)`.
4. Chien search finds the positions; the number of roots must equal `deg Λ`.
5. Forney's algorithm with `Ω(x) = S(x) Λ(x) mod x^p` gives the magnitudes.
6. The syndromes of the corrected word must all be zero.

The receiver first decodes with the least confident symbols as erasures (at most `p` of them). If that
fails it tries again without erasures. The CRC-32 is checked afterwards to catch miscorrections.

## 7. Payload types

| Type | Name | Encoding |
|---|---|---|
| 0 | Text | UTF-8 |
| 1 | Link | UTF-8 URL |
| 2 | Wi-Fi | UTF-8 string in the QR-code format `WIFI:T:<WPA\|WEP\|NONE>;S:<ssid>;P:<password>;;` with `\` escaping of `\ ; , : "` |
| 3 | Sketch | Stroke list, see below |

### Sketch encoding

Points lie on a 64 × 64 grid. Each stroke is encoded as

```
[count] [x0] [y0] [step 1] … [step count−1]
```

where every step byte holds a signed 4-bit x delta (high nibble) and a signed 4-bit y delta (low nibble),
each in −8 … 7. The encoder inserts intermediate points so that no step exceeds 7 grid cells; strokes
with more than 255 points are split. Before encoding, free-hand strokes are simplified with
Ramer–Douglas–Peucker, starting at a tolerance of 0.4 % of the canvas size and increasing it by
1.5× until the encoding fits into one frame (160 bytes).

## 8. Receiver state machine

`StreamingDecoder` accepts audio chunks of any size and emits events:

```
Searching ──preamble──► Header ──valid──► Payload ──n symbols──► FrameDecoded / FrameFailed
    ▲                      │ invalid                                   │
    └──────────────────────┴───────────────────────────────────────────┘
```

Events: `SyncFound(sampleIndex, score)`, `SymbolDecoded(section, index, count, reading)`,
`HeaderDecoded(header)`, `FrameDecoded(frame)` and `FrameFailed(reason)` with the reasons
`InvalidHeader`, `TooManyErrors` and `ChecksumMismatch`. The app mutes its own receiver while it
transmits.

## 9. Verification

The `modem` test suite (`./gradlew :modem:allTests`) runs on JVM, Android, Kotlin/Wasm and iOS and covers:

* FFT against a naive DFT, Goertzel tone selectivity, the CRC-32 check value `0xCBF43926`;
* Reed–Solomon with random errors, errors + erasures and over-capacity damage (200 random cases each);
* end-to-end frames through `ChannelSimulator` at 0 dB SNR (Robust and Melodic), 6 dB (Fast), with 60 % echo at
  12 ms, 44.1 ↔ 48 kHz conversion, ±300 ppm clock drift, a 400 ms noise burst, the maximum payload,
  arbitrary chunk sizes and back-to-back frames;
* 10 s of noise without any false synchronisation.
