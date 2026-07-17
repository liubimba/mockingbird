# mockingbird

**A stand-in for the camera you cannot keep plugged in.** Point it at a pcap capture and it
serves that traffic over RTSP, note for note, the same bytes on every run.

Named after the bird that repeats what it hears, because that is the whole trick: mockingbird
does not encode anything. It works out what streams live in the capture, describes them to the
client, and replays each packet at the moment the capture says it belongs.

```bash
mvn package
java --enable-preview -jar target/mockingbird-0.0.1.jar \
  --pcap bunny.pcapng --map 97:H264/90000 --map 96:mpeg4-generic/12000/2
ffplay rtsp://localhost:5554/bunny
```

## Why replay instead of encode

A normal RTSP server encodes video on the fly: it needs an encoder, burns CPU, and emits a
slightly different bitstream every run. That makes it awkward to test against.

Replaying a capture inverts those trade-offs:

- **Deterministic** — every run emits the same RTP bytes, so client bugs reproduce reliably.
- **No encoder** — no FFmpeg pipeline, no codec dependencies, no CPU cost.
- **Real traffic** — actual captured packets, quirks and all, not what a generator invented.
- **Whatever you captured** — a camera, a conference, the stream that misbehaved once at 3am.

It makes a useful fixture for RTSP/RTP client work: point a player at it and you get a stable,
repeatable stream.

## What it works out on its own, and what it cannot

Read from the capture, no help needed:

- how many RTP streams there are, and which port each was delivered to
- each stream's payload type and SSRC
- which packets are RTP, as opposed to the RTCP and strays sharing the capture
- the exact timing of every packet

**Not in the capture, and not guessable:** the codec behind a *dynamic* payload type (96–127).
Those numbers mean whatever the original session's SDP said they meant, and that SDP is not in
the packets. Rather than guess and hand a client a stream it will decode into noise, the server
says what it needs:

```
$ java -jar target/mockingbird-0.0.1.jar --pcap bunny.pcapng
payload type 97 is dynamic, so the capture cannot say what codec it is.
Supply it, for example: --map 97:H264/90000
```

Static payload types (0–34) are assigned globally by [RFC 3551][rfc3551] and are named from that
registry automatically — a G.711 capture needs no `--map` at all.

## Usage

```
--pcap <file>        capture to serve (required)
--port <n>           RTSP port to listen on (default 5554)
--name <text>        session name in the SDP (default: the capture's file name)
--map <pt>:<enc>     encoding for a dynamic payload type, e.g. --map 97:H264/90000
--fmtp <pt>:<params> format parameters for a payload type
--sdp <file>         use this session description verbatim instead of building one
--help               show this message
```

Some depacketisers need more than an encoding name: AAC over `mpeg4-generic` cannot be parsed at
all without its `fmtp`, and H.264 needs `packetization-mode` before it will reassemble fragments.
Serving the bundled capture in full:

```bash
java --enable-preview -jar target/mockingbird-0.0.1.jar --pcap bunny.pcapng \
  --map 97:H264/90000 --map 96:mpeg4-generic/12000/2 \
  --fmtp 97:'packetization-mode=1;profile-level-id=42C01E;sprop-parameter-sets=Z0LAHtkDxWhAAAADAEAAAAwDxYuS,aMuMsg==' \
  --fmtp 96:'profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1490'
```

It reports what it found on startup, so a surprise shows up before a client trips over it:

```
Found 2 RTP stream(s):
  trackID=1  port 49190  payload type 97  ssrc 2093403070  1052 packets
  trackID=2  port 49188  payload type 96  ssrc 2066936511  368 packets
Serving rtsp://localhost:5554/bunny
```

## How it works

```
                    ┌── CaptureAnalyzer ──▶ RtpTrack[]   what is in here?
   capture.pcap ────┤
                    └── SdpBuilder ───────▶ SDP          how do I describe it?

RTSP client ──TCP:5554──▶ RTSPServer        OPTIONS/DESCRIBE/SETUP/PLAY/PAUSE/TEARDOWN
                              │ PLAY
                              ▼
                    ┌── UdpFrame ─────────▶ where is the RTP in this frame?
   capture.pcap ────┤
                    └── ReplayClock ──────▶ when is this packet due?
                              │
RTSP client ◀──UDP RTP────────┘
```

| | |
|---|---|
| `Capture` | the only thing that touches libpcap; the rest works on plain bytes |
| `UdpFrame` | finds the UDP payload, computing offsets per frame rather than assuming them |
| `RtpTrack` | one stream — port, payload type, SSRC — plus the RFC 3551 registry |
| `CaptureAnalyzer` | groups packets into streams, drops RTCP and strays |
| `SdpBuilder` | describes the streams, and refuses to guess a dynamic payload type |
| `ReplayClock` | when each packet is due |
| `RtspHeaders` | reads RTSP fields by name, since [RFC 2326][rfc2326] fixes no header order |
| `Options` | the command line |

Three details worth calling out:

**Offsets are computed, not assumed.** Captures off real networks carry IPv4 options and 802.1Q
tags, both of which move the payload. A fixed offset works right up until it silently reads the
wrong bytes.

**Tracks are identified by `trackID`, not by SETUP order.** The control URL says which stream a
SETUP means, so a client that sets its tracks up out of order still gets each one on the right
port.

**Pacing uses absolute deadlines.** The first packet anchors the clock; every later one is due at
that anchor plus the gap recorded in the capture. A packet that goes out late never pushes back
the ones behind it, so lateness cannot compound.

## Running

Needs JDK 21 and libpcap. jnetpcap resolves `libpcap.so`, which on Debian/Ubuntu ships with the
dev package:

```bash
sudo apt install libpcap-dev
```

```bash
mvn package     # builds target/mockingbird-0.0.1.jar with its dependencies in target/lib
mvn test        # 67 tests, none of which need libpcap
```

`--enable-preview` is needed at startup because the Foreign Function & Memory API the capture
reader uses is still a preview feature on Java 21.

## Tech

- **Java 21** — `java.lang.foreign` for reading packet buffers out of native memory
- **jnetpcap-wrapper 2.3.1+jdk21** — libpcap bindings (the plain `2.3.x` artifacts target Java 22
  and will not load on 21)
- **Maven**, **JUnit 5**

## Verified against

`ffmpeg` and `ffprobe` negotiate both tracks of the bundled capture and decode them as
`h264 240x160 24 fps` and `aac (LC) 12000 Hz stereo`, with no depacketiser errors.

Replaying that capture's 1420 media packets takes **38.89 s** against the **38.73 s** their own
timestamps span — the original timing reproduced to within **0.4%**.

## Scope and limitations

A focused test tool, not a production media server. Deliberately out of scope:

- **One client at a time** — a single RTSP session on one connection.
- **Ethernet + IPv4 + UDP only** — IPv6, tunnelling and other link types are skipped rather than
  guessed at.
- **No RTCP** — sender reports and receiver feedback are not implemented.
- **No seeking** — `PLAY` resumes where the capture left off; `Range` is ignored.
- **RTP passes through verbatim** — sequence numbers and timestamps are the capture's own and are
  not rewritten, so a client strict about SSRC or clock continuity may object.

[rfc2326]: https://www.rfc-editor.org/rfc/rfc2326
[rfc3551]: https://www.rfc-editor.org/rfc/rfc3551#section-6
