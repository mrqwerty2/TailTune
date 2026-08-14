# TailTune Protocol v1

Service discovery type: `_tailtune._tcp`  
Default TCP port: `8787`

## Authentication

Every API/WebSocket session requires the 32-character Base64URL access token generated on Android.

Native WebSocket client header:

```text
X-TailTune-Token: <token>
```

The browser fallback currently supplies the token on its WebSocket query because browser WebSocket APIs cannot set arbitrary handshake headers.

## Request envelope

```json
{
  "type": "request",
  "requestId": "client-generated-id",
  "operation": "ping"
}
```

Maximum request size: 64 KiB.

## Response envelope

Success:

```json
{
  "type": "response",
  "requestId": "client-generated-id",
  "ok": true,
  "data": {}
}
```

Failure:

```json
{
  "type": "response",
  "requestId": "client-generated-id",
  "ok": false,
  "error": "safe user-facing message"
}
```

## Operations

### `ping`

No fields. Returns `{ "pong": <epoch-ms> }`.

### `snapshot`

Returns server/library/playback/download snapshot.

### `playlist`

```json
{ "playlistId": "..." }
```

Returns playlist summary plus song array.

### `playPlaylist`

```json
{ "playlistId": "...", "startIndex": 0 }
```

Starts playback and returns playback state including queue.

### `control`

```json
{ "action": "play|pause|toggle|next|previous" }
```

Additional forms:

```json
{ "action": "seek", "positionMs": 12345 }
{ "action": "jump", "index": 5 }
```

### `queue`

```json
{ "action": "clear" }
{ "action": "remove", "index": 3 }
{ "action": "move", "from": 3, "to": 1 }
```

### `sync`

```json
{ "full": false }
```

A normal sync updates lightweight summaries. A full sync downloads every playlist detail and is intended for explicit repair/refresh.

### `download`

```json
{ "playlistId": "..." }
```

Queues an offline download.

### `removeDownload`

```json
{ "playlistId": "..." }
```

Cancels/removes that playlist's offline request while retaining shared files required by another offline playlist.

## Server-push events

```json
{
  "type": "playback|library|downloads|snapshot",
  "data": {},
  "timestamp": 1780000000000
}
```

Playback events include the full queue only when the queue revision changes.

## Authorization failure

An unauthorized WebSocket receives an `authorization_error` message when possible and is then closed. Native clients should not continuously reconnect with a known-invalid token.

## Compatibility

Bonjour TXT currently advertises `protocol=1`. Clients should reject/feature-gate future incompatible protocol versions rather than guessing.
