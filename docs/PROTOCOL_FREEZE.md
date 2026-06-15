# Protocol Freeze

This document marks the Tripper Dash wire protocol as frozen. Product features, UI,
rendering, storage, and navigation logic may evolve, but the connection, socket,
auth, acknowledgement, projection, and RTP behaviors below must not change without
fresh packet captures and explicit review.

## Frozen Files

Treat these files as protocol-critical:

- `app/src/main/java/com/example/northstar/dash/DashSession.kt`
- `app/src/main/java/com/example/northstar/dash/DashSocket.kt`
- `app/src/main/java/com/example/northstar/dash/DashAuth.kt`
- `app/src/main/java/com/example/northstar/dash/protocol/DashCommands.kt`
- `app/src/main/java/com/example/northstar/dash/protocol/K1GPacket.kt`
- `app/src/main/java/com/example/northstar/dash/video/RtpPacketizer.kt`
- `app/src/main/java/com/example/northstar/dash/video/NalProcessor.kt`
- `app/src/main/java/com/example/northstar/dash/video/DashEncoder.kt`

Read-only wiring from other modules is acceptable when needed, but protocol packet
constants, packet ordering, socket targets, acknowledgement handling, and RTP
packetization rules are frozen.

## DashSession Connection Sequence

`DashSession` must preserve the current connection order:

1. Open sockets through `DashSocket`, with RX bound before the first burst packet.
2. Create `DashAuth` from the exact dash SSID.
3. Start the receive loop before sending the initial burst.
4. Start the 1 Hz status heartbeat.
5. Enter `AUTHENTICATING`.
6. Send `DashCommands.initialBurst(HOSTNAME)` in order, with the current burst pause.
7. Wait for auth confirmation from the dash.
8. Enter nav mode in the current route/projection sequence.
9. Enter `READY`.
10. Only after `READY`, `startStreaming()` may enter `STREAMING` and launch projection heartbeat, route-card keep-alive, and active nav info.

Do not reorder, combine, remove, or delay these phases.

## DashSocket Ports And Targets

`DashSocket` ports and targets are frozen:

- Control TX binds local UDP `:2000`.
- Control TX sends to broadcast `192.168.1.255:2000`.
- Control RX binds local UDP `:2002`.
- RTP uses an ephemeral local socket.
- RTP sends to dash target `192.168.1.1:5000`.
- Control packets must continue to patch the rolling K1G sequence byte on send.
- The control plane must keep using broadcast; do not switch it to the dash IP.

The RX socket must be open before the first TX control packet to receive early dash
responses and avoid disturbing the dash state machine.

## Auth Packet Flow

The auth flow is frozen:

- Initial burst begins with `q3c.e` auth request from `DashCommands.authRequest()`.
- Incoming `07 00` / `07 03` auth TLVs are handled by `DashAuth.ingest(...)`.
- On RSA public key receipt, send `q3c.d` from `DashCommands.authSendKey(...)`.
- The `q3c.d` ciphertext must remain exactly 128 bytes.
- Auth is confirmed only by the existing `07 01 01` flow.
- Auth rejection must continue to reset auth and retry `DashCommands.authRequest()` within the existing retry behavior.
- Do not alter AES/RSA key derivation, SSID input, packet TLV types, or confirmation semantics without new captures.

## Frame Decoded ACK Packets

The dash sends per-frame decoded notifications while streaming. These ACK replies are
mandatory and frozen:

- Incoming `09 06 55` must reply with `DashCommands.frameDecodedIdr()` (`q3c.L2`).
- Incoming `09 04 55` must reply with `DashCommands.frameDecodedP()` (`q3c.K2`).
- These ACKs must be handled inside the receive loop for the full session lifetime.
- Do not throttle, batch, remove, or move these replies out of the RX path.

Button events are also acknowledged in the RX path:

- Incoming `09 00 ... <code>` must send `DashCommands.buttonAck(code)`.
- UI handling may change, but the protocol echo ACK must remain.

## Route Card And Projection Sequence

The nav/projection entry sequence in `DashSession.enterNavMode(...)` is frozen:

1. Send `DashCommands.navContext()`.
2. Send `DashCommands.emptyLists()`.
3. Send route card with `projectionOn = false` four times.
4. Send `DashCommands.projectionFrame()`.
5. Send `DashCommands.navPlaceholder()`.
6. Send `DashCommands.navStart()` exactly once.
7. Send route card with `projectionOn = true`.

While streaming:

- Projection heartbeat must continue sending `DashCommands.projectionFrame()` at the current heartbeat cadence.
- Route-card keep-alive must continue sending `liveRouteCard(projectionOn = true)` at the current keep-alive cadence.
- Active nav info must continue using `DashCommands.activeNavPacket(...)` when live nav info is active.

The route-card template and patching behavior in `DashCommands.routeCard(...)` are
frozen. Do not let captured template values leak into live guidance fields.

## RTP Target And Packetization Behavior

RTP behavior is frozen:

- RTP target is `192.168.1.1:5000`.
- Payload type is `96`.
- RTP timestamp clock is `90 kHz`.
- Max RTP payload is `1380` bytes.
- Large NAL units must use FU-A fragmentation.
- STAP-A aggregation must not be used.
- Marker bit must be set only on the last RTP packet of each access unit.
- RTP sequence must increment per emitted RTP packet.

The encoder/NAL/RTP pipeline must preserve:

- H.264 AVC Baseline stream for the dash.
- SPS/PPS handling in `NalProcessor`.
- RTP emission through `DashSession.sendRtp(...)` to `DashSocket.sendRtp(...)`.

Rendering content may change before encoding, but the encoded stream format,
packetization rules, RTP target, and RTP send path must not change.

## Review Rule

Any change touching the frozen files above must answer:

- Does this change alter the on-wire bytes?
- Does this change alter packet order, cadence, target address, or port?
- Does this change alter auth, ACK, projection, route-card, or RTP semantics?

If the answer to any question is yes or uncertain, the change is out of scope for
ordinary UI/product work and requires protocol review with captures.
