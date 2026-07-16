# rtsp-pcap-server

An RTSP server in Java that streams video by **replaying real RTP traffic from a pcap
capture** — with no encoder involved.

The server speaks RTSP to the client, then reads packets straight out of `bunny.pcapng`
and forwards their RTP payloads over UDP, releasing each one at the moment the capture
says it belongs.

```bash
mvn compile
mvn exec:exec
ffplay rtsp://localhost:5554/bunny
```

## Why replay instead of encode

A normal RTSP server encodes video on the fly: it needs an encoder, burns CPU, and emits a
slightly different bitstream every run. That makes it awkward to test against.

Replaying a capture inverts those trade-offs:

- **Deterministic** — every run emits the same RTP bytes, so client bugs reproduce reliably.
- **No encoder** — no FFmpeg pipeline, no codec dependencies, no CPU cost.
- **Real traffic** — the packets are an actual captured stream, quirks and all, rather than
  something a synthetic generator invented.

It makes a useful fixture for RTSP/RTP client work: point a player at it and you get a
stable, repeatable stream.

## How it works

```
RTSP client ──TCP:5554──▶ RTSPServer            RTSP state machine, SDP
                              │
                              │ PLAY
                              ▼
                         bunny.pcapng ──▶ jnetpcap (offline, BPF "udp")
                              │
                              │  ReplayClock  → wait out the capture's own gaps
                              │  strip 42-byte Ethernet+IPv4+UDP header
                              │  route by the port the track used in the capture
                              ▼
RTSP client ◀──UDP RTP─── DatagramSocket
```

**RTSP** (`RTSPServer.java`) — a hand-written state machine over `INIT → READY → PLAYING`,
handling `OPTIONS`, `DESCRIBE`, `SETUP`, `PLAY`, `PAUSE` and `TEARDOWN`. `DESCRIBE` answers
with an SDP announcing the AAC and H.264 tracks the capture carries. Header fields are
looked up by name, never by line number — [RFC 2326][rfc] fixes no order, and clients
genuinely differ (ffmpeg sends `Accept` ahead of `CSeq`).

**Packet extraction** — the capture is opened offline behind a `udp` BPF filter. Each frame's
RTP payload is sliced at a fixed 42-byte offset (14 Ethernet + 20 IPv4 + 8 UDP) through the
Java FFM API, so packet bytes are read straight out of native memory.

**Track routing** (`destPortFor`) — the capture delivered audio to port 49188 and video to
49190. The SDP announces audio first, so the client's first `SETUP` is audio, and each track
is forwarded to the RTP port that client negotiated for it.

**Pacing** (`ReplayClock.java`) — the first packet anchors the clock, and every later packet
is due at that anchor plus the gap recorded in the capture. Deadlines are absolute rather
than relative, so a packet that goes out late never pushes back the ones behind it and
lateness cannot compound.

## Tech

- **Java 21** — the Foreign Function & Memory API (`java.lang.foreign`) is still a preview
  feature here, hence `--enable-preview`
- **jnetpcap-wrapper 2.3.1+jdk21** — libpcap bindings (the plain `2.3.x` artifacts target
  Java 22 and will not load on 21)
- **Maven**, **JUnit 5**

## Running

Needs JDK 21 and libpcap. jnetpcap resolves `libpcap.so`, which on Debian/Ubuntu ships with
the dev package:

```bash
sudo apt install libpcap-dev
```

```bash
mvn compile
mvn exec:exec          # serves on rtsp://localhost:5554
```

`exec:exec` forks a JVM because the classes are preview-compiled; `exec:java` would run them
inside Maven's own JVM, which starts without preview enabled and rejects them.

Point any RTSP client at it:

```bash
ffplay rtsp://localhost:5554/bunny
vlc rtsp://localhost:5554/bunny
ffprobe -rtsp_transport udp rtsp://localhost:5554/bunny
```

Tests cover the pacing arithmetic and the header parsing:

```bash
mvn test
```

## Verified against

`ffprobe` negotiates both tracks and reports them as `aac` and `h264 240x160`. Replaying the
bundled capture takes 38.9 s of wall time against the 39.7 s its own timestamps span, so the
original timing is reproduced to within about 2%.

## Scope and limitations

A focused test tool, not a production media server. Deliberately out of scope:

- **One client at a time** — a single RTSP session, served on one connection.
- **One fixed capture** — the SDP and the 49188/49190 track mapping describe the bundled
  `bunny.pcapng`; another capture needs both updated.
- **No RTCP** — sender reports and receiver feedback are not implemented.
- **No seeking** — `PLAY` resumes where the capture left off; `Range` is ignored.
- **RTP passes through verbatim** — sequence numbers and timestamps are the capture's own and
  are not rewritten, so a client that is strict about SSRC or clock continuity may object.

[rfc]: https://www.rfc-editor.org/rfc/rfc2326
